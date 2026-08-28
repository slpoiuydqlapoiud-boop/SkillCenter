package com.huawei.skillcenter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcReleaseRecordStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbc;
    private boolean dockerAvailable;

    @BeforeAll
    void startPostgres() {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable) return;
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearRecords() {
        if (dockerAvailable) jdbc.update("delete from release_records");
    }

    @Test
    void loadReturnsEmptyWhenNoReleaseExists() {
        requireDocker();

        assertThat(store().findAll(null, null, null, null)).isEmpty();
    }

    @Test
    void createAndReplaceRoundTripsControlledReleaseState() {
        requireDocker();
        JdbcReleaseRecordStore store = store();
        ReleaseRecord requested = request("release-jdbc", "skill-jdbc", "1.0.0", "idem-jdbc", NOW);

        store.create(requested);
        ReleaseRecord approved = requested.approve("reviewer", NOW.plusSeconds(1));
        store.replace(approved);

        assertThat(store.find("release-jdbc")).contains(approved);
        assertThat(store.findByIdempotencyKey("idem-jdbc")).contains(approved);
        assertThat(store.findActiveBusinessKey("skill-jdbc", "1.0.0", ReleaseEnvironment.STAGING))
                .contains(approved);
    }

    @Test
    void databaseConstraintsBecomeStableReleaseConflicts() {
        requireDocker();
        JdbcReleaseRecordStore store = store();
        store.create(request("release-one", "skill-jdbc", "1.0.0", "idem-one", NOW));

        assertThatThrownBy(() -> store.create(request("release-two", "skill-jdbc", "1.0.0", "idem-two",
                NOW.plusSeconds(1))))
                .isInstanceOf(ReleaseConflictException.class)
                .hasMessageContaining("active release");
    }

    @Test
    void replaceRejectsImmutableReleaseContext() {
        requireDocker();
        JdbcReleaseRecordStore store = store();
        ReleaseRecord requested = request("release-immutable", "skill-jdbc", "1.0.0", "idem-immutable", NOW);
        store.create(requested);
        ReleaseRecord changed = ReleaseRecord.request("release-immutable", "skill-other", "1.0.0",
                "b".repeat(64), ReleaseEnvironment.STAGING, ReleaseGateSnapshot.passed(NOW), "idem-immutable",
                "admin", NOW);

        assertThatThrownBy(() -> store.replace(changed))
                .isInstanceOf(ReleaseConflictException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void concurrentCreatesAllowOnlyOneActiveBusinessKey() throws Exception {
        requireDocker();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> first = () -> createOutcome("release-concurrent-1", "idem-concurrent-1");
            Callable<String> second = () -> createOutcome("release-concurrent-2", "idem-concurrent-2");
            Future<String> firstResult = executor.submit(first);
            Future<String> secondResult = executor.submit(second);

            assertThat(java.util.List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder("created", "conflict");
            assertThat(jdbc.queryForObject("select count(*) from release_records where skill_id = ? and version = ?",
                    Long.class, "skill-jdbc", "1.0.0")).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private String createOutcome(String releaseId, String idempotencyKey) {
        try {
            store().create(request(releaseId, "skill-jdbc", "1.0.0", idempotencyKey, NOW));
            return "created";
        } catch (ReleaseConflictException exception) {
            return "conflict";
        }
    }

    private JdbcReleaseRecordStore store() {
        return new JdbcReleaseRecordStore(jdbc, new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable,
                "CAPABILITY_SKIP: Docker is unavailable; PostgreSQL release integration cannot run");
    }

    private ReleaseRecord request(String releaseId, String skillId, String version,
                                  String idempotencyKey, Instant requestedAt) {
        return ReleaseRecord.request(releaseId, skillId, version, "a".repeat(64),
                ReleaseEnvironment.STAGING, ReleaseGateSnapshot.passed(requestedAt), idempotencyKey,
                "admin", requestedAt);
    }
}
