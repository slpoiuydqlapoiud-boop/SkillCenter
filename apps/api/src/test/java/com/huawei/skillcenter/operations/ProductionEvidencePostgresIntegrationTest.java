package com.huawei.skillcenter.operations;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionEvidencePostgresIntegrationTest {
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
    void clearEvidence() {
        if (dockerAvailable) jdbc.update("delete from skill_production_evidence");
    }

    @Test
    void upsertRoundTripsSafeMetadataAndAdvancesRevision() {
        requireDocker();
        JdbcProductionEvidenceStore store = store();
        ProductionEvidence accepted = evidence("DATABASE_CAPACITY_SLO", 1);

        assertThat(store.upsert(accepted, 0)).isEqualTo(accepted);
        assertThat(store.find("DATABASE_CAPACITY_SLO")).contains(accepted);
        assertThat(store.findAll()).extracting(ProductionEvidence::evidenceId)
                .containsExactly("DATABASE_CAPACITY_SLO");
        assertThat(jdbc.queryForObject("select revision from skill_production_evidence where evidence_id = ?",
                Long.class, "DATABASE_CAPACITY_SLO")).isEqualTo(1L);
    }

    @Test
    void staleRevisionIsRejectedWithoutOverwritingStoredEvidence() {
        requireDocker();
        JdbcProductionEvidenceStore store = store();
        ProductionEvidence original = evidence("SSO_ORGANIZATION", 1);
        store.upsert(original, 0);

        ProductionEvidence next = new ProductionEvidence("SSO_ORGANIZATION", "ACCEPTED", "admin", NOW,
                NOW.plusSeconds(7200), "change-2026-002", "updated", 2, "admin", NOW.plusSeconds(1));
        assertThatThrownBy(() -> store.upsert(next, 0))
                .isInstanceOf(ProductionEvidenceConflictException.class);
        assertThat(store.find("SSO_ORGANIZATION")).contains(original);
    }

    private JdbcProductionEvidenceStore store() {
        return new JdbcProductionEvidenceStore(jdbc,
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private ProductionEvidence evidence(String id, int revision) {
        return new ProductionEvidence(id, "ACCEPTED", "admin", NOW,
                NOW.plusSeconds(3600), "change-2026-001", "validated", revision, "admin", NOW);
    }

    private void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable,
                "CAPABILITY_SKIP: Docker is unavailable; PostgreSQL production evidence integration cannot run");
    }
}
