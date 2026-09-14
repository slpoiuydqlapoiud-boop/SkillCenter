package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.MysqlGovernanceStateRepository;
import com.huawei.skillcenter.governance.GovernanceStateRepository.GovernanceState;
import com.huawei.skillcenter.quality.MysqlQualityEvidenceStore;
import com.huawei.skillcenter.quality.QualityEvidenceState;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the department-only MySQL migration and JSON aggregate write/read path. */
@Testcontainers(disabledWithoutDocker = true)
class MysqlDepartmentPersistenceIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("skillcenter")
            .withUsername("skillcenter")
            .withPassword("local-only");

    @Test
    void migratesSharedDocumentTableAndPersistsQualityEvidence() {
        try (HikariDataSource dataSource = dataSource()) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration-mysql")
                    .load()
                    .migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            MysqlGovernanceStateRepository governance = new MysqlGovernanceStateRepository(
                    jdbc, new ObjectMapper(), transactionManager);
            MysqlQualityEvidenceStore store = new MysqlQualityEvidenceStore(
                    jdbc, new ObjectMapper(), transactionManager);

            GovernanceState seeded = governance.loadOrSeed(GovernanceSnapshot::empty);
            assertThat(seeded.revision()).isZero();
            assertThat(governance.load()).contains(seeded);
            assertThat(governance.replace(0L, GovernanceSnapshot.empty()).revision()).isEqualTo(1L);

            QualityEvidenceState empty = emptyState();
            store.save(empty);
            assertThat(store.load()).isEqualTo(empty);

            store.update(current -> current);

            assertThat(jdbc.queryForObject(
                    "select document_schema_version from department_json_documents where document_key = ?",
                    Integer.class, "quality-evidence")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select revision from department_json_documents where document_key = ?",
                    Long.class, "quality-evidence")).isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                    "select revision from department_platform_state where state_key = ?",
                    Long.class, "governance-state")).isEqualTo(1L);
            assertThat(Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration-mysql").load().info().current().getVersion().getVersion())
                    .isEqualTo("2");
        }
    }

    private HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private QualityEvidenceState emptyState() {
        return new QualityEvidenceState(List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
