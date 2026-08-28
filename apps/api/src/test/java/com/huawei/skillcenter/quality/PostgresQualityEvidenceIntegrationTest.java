package com.huawei.skillcenter.quality;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresQualityEvidenceIntegrationTest {
    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbcTemplate;
    private boolean dockerAvailable;

    @BeforeAll
    void startPostgres() {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable) return;
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        jdbcTemplate = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void resetAggregate() {
        if (dockerAvailable) jdbcTemplate.update("delete from skill_quality_evidence_state");
    }

    @Test
    void loadReturnsEmptyStateWhenAggregateRowDoesNotExist() {
        requireDocker();
        assertThat(store().load()).isEqualTo(empty());
    }

    @Test
    void saveThenLoadRoundTripsQualityEvidenceAndIncrementsRevision() {
        requireDocker();
        JdbcQualityEvidenceStore store = store();
        QualityEvidenceState state = emptyStateWithRule("quality-v1");

        store.save(state);

        assertThat(store.load()).isEqualTo(state);
        assertThat(revision()).isEqualTo(1L);
    }

    @Test
    void saveRejectsInvalidForeignEvidenceBeforeDatabaseWrite() {
        requireDocker();
        JdbcQualityEvidenceStore store = store();

        assertThatThrownBy(() -> store.save(stateWithSnapshotReferencingUnknownRun()))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from skill_quality_evidence_state", Long.class))
                .isZero();
    }

    @Test
    void updateLocksTheAggregateAndDoesNotSilentlyLoseAConcurrentRevision() throws Exception {
        requireDocker();
        JdbcQualityEvidenceStore store = store();
        store.save(emptyStateWithRule("quality-v1"));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<QualityEvidenceState> first = executor.submit(() -> store().update(state -> {
                firstLocked.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("first update was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return emptyStateWithRule("quality-v2");
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<QualityEvidenceState> second = executor.submit(() -> store().update(state -> {
                secondEntered.countDown();
                return emptyStateWithRule("quality-v3");
            }));
            assertThat(secondEntered.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).rules().version()).isEqualTo("quality-v2");
            assertThat(second.get(5, TimeUnit.SECONDS).rules().version()).isEqualTo("quality-v3");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
        assertThat(revision()).isEqualTo(3L);
        assertThat(store.load().rules().version()).isEqualTo("quality-v3");
    }

    @Test
    void failedTransactionLeavesThePreviousPayloadAndRevisionIntact() {
        requireDocker();
        JdbcQualityEvidenceStore store = store();
        QualityEvidenceState original = emptyStateWithRule("quality-v1");
        store.save(original);

        assertThatThrownBy(() -> store.update(state -> stateWithSnapshotReferencingUnknownRun()))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
        assertThat(store.load()).isEqualTo(original);
        assertThat(revision()).isEqualTo(1L);
    }

    @Test
    void clearIsIdempotentAndLeavesAValidEmptyAggregate() {
        requireDocker();
        JdbcQualityEvidenceStore store = store();

        store.clear();
        store.clear();

        assertThat(store.load()).isEqualTo(empty());
        assertThat(revision()).isEqualTo(2L);
    }

    @Test
    void revisionOverflowLeavesPayloadAndRevisionIntact() throws Exception {
        requireDocker();
        QualityEvidenceState original = emptyStateWithRule("quality-v1");
        String payload = new ObjectMapper().findAndRegisterModules().writeValueAsString(original);
        jdbcTemplate.update("insert into skill_quality_evidence_state (aggregate_key, document_schema_version, revision, payload, updated_at) values (?, ?, ?, ?::jsonb, current_timestamp)",
                "quality-evidence", 1, Long.MAX_VALUE, payload);

        assertThatThrownBy(() -> store().update(state -> emptyStateWithRule("quality-v2")))
                .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
        assertThat(store().load()).isEqualTo(original);
        assertThat(revision()).isEqualTo(Long.MAX_VALUE);
    }

    private JdbcQualityEvidenceStore store() {
        return new JdbcQualityEvidenceStore(jdbcTemplate, new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
    }

    private void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable,
                "CAPABILITY_SKIP: Docker is unavailable; PostgreSQL Testcontainers integration cannot run");
    }

    private QualityEvidenceState emptyStateWithRule(String version) {
        return new QualityEvidenceState(List.of(), new QualityRuleSet("quality", version, 70, 0.7, 70),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private QualityEvidenceState stateWithSnapshotReferencingUnknownRun() {
        return new QualityEvidenceState(List.of(), null, List.of(),
                List.of(new QualitySnapshot("missing-run", "skill-a", "1.0.0", "smoke", "1.0",
                        "runner", "provider", "test", Instant.parse("2026-08-25T00:00:00Z"),
                        100, 1, 1, true, "quality-v1", 100, 1.0,
                        QualityGateStatus.PASSED, List.of())), List.of(), List.of(), List.of());
    }

    private long revision() {
        return jdbcTemplate.queryForObject(
                "select revision from skill_quality_evidence_state where aggregate_key = ?", Long.class,
                "quality-evidence");
    }

    private QualityEvidenceState empty() {
        return new QualityEvidenceState(List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
