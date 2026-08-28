package com.huawei.skillcenter.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionConfiguration;
import com.huawei.skillcenter.lifecycle.PostgresSkillLifecycleProjectionStore;
import com.huawei.skillcenter.quality.JdbcQualityEvidenceStore;
import com.huawei.skillcenter.quality.QualityEvidenceRepository;
import com.huawei.skillcenter.quality.QualityEvidenceStore;
import com.huawei.skillcenter.release.JdbcReleaseRecordStore;
import com.huawei.skillcenter.release.ReleaseRecordRepository;
import com.huawei.skillcenter.release.ReleaseRecordStore;

import javax.sql.DataSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceBackendConfigurationTest {
    @TempDir
    Path tempDir;

    private final ApplicationContextRunner persistenceContext = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(
                    PersistenceControlProperties.class,
                    PostgresPersistenceProperties.class,
                    PostgresPersistenceConfiguration.class,
                    SkillLifecycleProjectionConfiguration.class,
                    PersistenceConfiguration.class,
                    PersistenceBackendFixtureConfiguration.class);

    private final ApplicationContextRunner qualityEvidenceContext = persistenceContext
            .withUserConfiguration(QualityEvidenceRepositoryConfiguration.class);

    private final ApplicationContextRunner releaseContext = persistenceContext
            .withUserConfiguration(ReleaseRepositoryFixtureConfiguration.class,
                    ReleaseRecordStore.class, JdbcReleaseRecordStore.class);

    private final ApplicationContextRunner lifecycleProjectionReadinessContext = new ApplicationContextRunner()
            .withUserConfiguration(
                    LifecycleProjectionSchemaOneFixtureConfiguration.class,
                    SkillLifecycleProjectionConfiguration.class);

    @Test
    void jsonIsTheDefaultAndDoesNotRequireDatabaseConfiguration() {
        PersistenceControlProperties properties = new PersistenceControlProperties();

        assertThat(properties.normalizedBackend()).isEqualTo("json");
        assertThat(properties.normalizedQualityEvidenceBackend()).isEqualTo("json");
        assertThat(properties.normalizedReleaseBackend()).isEqualTo("json");
    }

    @Test
    void releaseBackendPostgresqlRequiresPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setReleaseBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("releaseBackend=postgresql requires backend=postgresql");
    }

    @Test
    void releaseMigrationDefinesTheReleaseRecordTableAndConflictIndexes() throws IOException {
        String migration = new ClassPathResource("db/migration/V4__create_release_records.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE release_records")
                .contains("gate_snapshot jsonb NOT NULL")
                .contains("release_records_idempotency_key_key")
                .contains("release_records_active_business_key_key")
                .contains("WHERE status NOT IN");
    }

    @Test
    void unknownBackendIsRejectedWithAStableValidationFailure() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setBackend("oracle");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("backend must be json or postgresql");
    }

    @Test
    void qualityEvidencePostgresqlRequiresPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setQualityEvidenceBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("qualityEvidenceBackend=postgresql requires backend=postgresql");
    }

    @Test
    void backendValuesAreNormalizedForConditionalWiring() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setBackend(" PostgreSQL ");
        properties.setQualityEvidenceBackend(" PostgreSQL ");

        assertThat(properties.normalizedBackend()).isEqualTo("postgresql");
        assertThat(properties.normalizedQualityEvidenceBackend()).isEqualTo("postgresql");
    }

    @Test
    void normalizedQualityEvidenceBackendSelectsExactlyOneRepository() {
        qualityEvidenceContext
                .withPropertyValues("skill-center.quality-evidence-backend= JSON ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(QualityEvidenceRepository.class)).hasSize(1);
                    assertThat(context).hasSingleBean(QualityEvidenceStore.class);
                    assertThat(context.getBeansOfType(JdbcQualityEvidenceStore.class)).isEmpty();
                });

        qualityEvidenceContext
                .withPropertyValues(
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.quality-evidence-backend= PostgreSQL ",
                        "skill-center.persistence.postgresql.url=jdbc:postgresql://127.0.0.1:1/skill_center",
                        "skill-center.persistence.postgresql.username=skill_center",
                        "skill-center.persistence.postgresql.password=secret",
                        "skill-center.persistence.postgresql.timeout=250ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(QualityEvidenceRepository.class)).hasSize(1);
                    assertThat(context).hasSingleBean(JdbcQualityEvidenceStore.class);
                    assertThat(context.getBeansOfType(QualityEvidenceStore.class)).isEmpty();
                });
    }

    @Test
    void normalizedReleaseBackendSelectsExactlyOneRepository() {
        releaseContext
                .withPropertyValues("skill-center.release-backend= JSON ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ReleaseRecordRepository.class)).hasSize(1);
                    assertThat(context).hasSingleBean(ReleaseRecordStore.class);
                    assertThat(context.getBeansOfType(JdbcReleaseRecordStore.class)).isEmpty();
                });

        releaseContext
                .withPropertyValues(
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.release-backend= PostgreSQL ",
                        "skill-center.persistence.postgresql.url=jdbc:postgresql://127.0.0.1:1/skill_center",
                        "skill-center.persistence.postgresql.username=skill_center",
                        "skill-center.persistence.postgresql.password=secret",
                        "skill-center.persistence.postgresql.timeout=250ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ReleaseRecordRepository.class)).hasSize(1);
                    assertThat(context).hasSingleBean(JdbcReleaseRecordStore.class);
                    assertThat(context.getBeansOfType(ReleaseRecordStore.class)).isEmpty();
                });
    }

    @Test
    void applicationTopLevelPostgresQualityEvidenceMarksCatalogAndBlocksExistingJsonSnapshot() throws Exception {
        PersistenceControlProperties jsonProperties = new PersistenceControlProperties();
        jsonProperties.setControlStorage("control");
        jsonProperties.setSnapshotStorage("snapshots");
        Files.createDirectories(tempDir.resolve("data/governance"));
        Files.writeString(tempDir.resolve("data/governance/state.json"), "{}");
        Path legacyQualityEvidence = tempDir.resolve("data/governance/quality-evidence.json");
        Files.writeString(legacyQualityEvidence, "{\"legacy\":true}");
        PersistenceArtifactCatalog jsonCatalog = new PersistenceArtifactCatalog(jsonProperties, tempDir);
        PersistenceSnapshotManifest jsonSnapshot = new PersistenceSnapshotService(
                jsonCatalog, jsonProperties, tempDir, new PersistenceIntegrityService(tempDir)).createSnapshot();
        byte[] sourceBefore = Files.readAllBytes(legacyQualityEvidence);
        byte[] manifestBefore = Files.readAllBytes(tempDir.resolve("snapshots")
                .resolve(jsonSnapshot.snapshotId()).resolve("manifest.json"));

        persistenceContext
                .withPropertyValues(
                        "skill-center.persistence.configured-root=" + tempDir,
                        "skill-center.persistence.backend= PostgreSQL ",
                        "skill-center.quality-evidence-backend= PostgreSQL ",
                        "skill-center.persistence.control-storage=control",
                        "skill-center.persistence.snapshot-storage=snapshots")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PersistenceArtifactCatalog.class).find("quality-evidence"))
                            .hasValueSatisfying(descriptor ->
                                    assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
                    assertThat(context.getBean(PersistenceSnapshotService.class)
                            .restorePreflight(jsonSnapshot.snapshotId()))
                            .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                                    PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                            .containsExactly("BLOCKED", "PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED");
                    assertThat(Files.readAllBytes(legacyQualityEvidence)).isEqualTo(sourceBefore);
                    assertThat(Files.readAllBytes(tempDir.resolve("snapshots")
                            .resolve(jsonSnapshot.snapshotId()).resolve("manifest.json"))).isEqualTo(manifestBefore);
                });
    }

    @Test
    void postgresStatusNeverContainsPasswordOrJdbcCredentials() {
        PersistenceBackendStatus status = PersistenceBackendStatus.failClosed(
                "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", "12", 4L);

        assertThat(status.toString()).doesNotContain("password", "jdbc:", "secret");
        assertThat(status.state()).isEqualTo("FAIL_CLOSED");
    }

    @Test
    void statusFactoryDoesNotProjectRawExceptionContent() {
        PersistenceBackendStatus status = PersistenceBackendStatus.failClosed(
                "postgresql", "jdbc:postgresql://db?password=secret", "12", 4L);

        assertThat(status.reasonCode()).isEqualTo("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThat(status.toString()).doesNotContain("jdbc:", "password", "secret");
    }

    @Test
    void statusFactoryReplacesUnsafeDiagnosticReasonCodes() {
        for (String unsafeReasonCode : List.of("SQL_ERROR", "PROMPT", "TRACE", "CONNECTION_REFUSED")) {
            PersistenceBackendStatus status = PersistenceBackendStatus.failClosed(
                    "postgresql", unsafeReasonCode, "12", 4L);

            assertThat(status.reasonCode()).isEqualTo("PERSISTENCE_CONTROL_PLANE_ERROR");
            assertThat(status.toString()).doesNotContain(unsafeReasonCode);
        }
    }

    @Test
    void jsonBackendReportsReadyStatusWithoutRevision() {
        PersistenceBackend backend = new JsonPersistenceBackend();

        assertThat(backend.backendId()).isEqualTo("json");
        assertThat(backend.status()).isEqualTo(PersistenceBackendStatus.ready("json", null, null));
    }

    @Test
    void postgresMigrationDefinesTheSingletonQualityEvidenceAggregate() throws IOException {
        String migration = new ClassPathResource("db/migration/V1__create_quality_evidence_state.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_quality_evidence_state")
                .contains("aggregate_key text PRIMARY KEY")
                .contains("document_schema_version integer NOT NULL")
                .contains("revision bigint NOT NULL")
                .contains("payload jsonb NOT NULL")
                .contains("updated_at timestamptz NOT NULL")
                .contains("skill_quality_evidence_state_revision_non_negative")
                .contains("skill_quality_evidence_state_payload_object")
                .contains("skill_quality_evidence_state_singleton_key")
                .contains("aggregate_key = 'quality-evidence'");
    }

    @Test
    void jsonContextDoesNotCreatePostgresInfrastructureOrRequireADatasourceUrl() {
        persistenceContext
                .withPropertyValues("skill-center.persistence.backend=json")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
                    assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
                    assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).isEmpty();
                    assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
                    assertThat(context.getBeansOfType(PostgresPersistenceBackend.class)).isEmpty();
                    assertThat(context.getBeansOfType(PersistenceBackend.class))
                            .hasSize(1)
                            .extractingByKey("jsonPersistenceBackend")
                            .isInstanceOf(JsonPersistenceBackend.class);
                });
    }

    @Test
    void jsonLifecycleProjectionBackendDoesNotCreateDatabaseInfrastructureOrPostgresProjectionStore() {
        persistenceContext
                .withPropertyValues(
                        "skill-center.persistence.backend=json",
                        "skill-center.lifecycle-projection.backend=json")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
                    assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
                    assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).isEmpty();
                    assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
                    assertThat(context.getBeansOfType(PostgresSkillLifecycleProjectionStore.class)).isEmpty();
                });
    }

    @Test
    void postgresqlLifecycleProjectionBackendDoesNotCreateProjectionStoreWhenA2PostgresqlIsNotReady() {
        persistenceContext
                .withPropertyValues(
                        "skill-center.persistence.backend=postgresql",
                        "skill-center.lifecycle-projection.backend=postgresql",
                        "skill-center.persistence.postgresql.url=jdbc:postgresql://127.0.0.1:1/skill_center",
                        "skill-center.persistence.postgresql.username=skill_center",
                        "skill-center.persistence.postgresql.password=secret",
                        "skill-center.persistence.postgresql.timeout=250ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PostgresPersistenceBackend.class).status())
                            .isEqualTo(PersistenceBackendStatus.failClosed(
                                    "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", null, null));
                    assertThat(context.getBeansOfType(PostgresSkillLifecycleProjectionStore.class)).isEmpty();
                });
    }

    @Test
    void postgresqlLifecycleProjectionBackendDoesNotCreateProjectionStoreBeforeSchemaVersionThree() {
        lifecycleProjectionReadinessContext
                .withPropertyValues(
                        "skill-center.persistence.backend=postgresql",
                        "skill-center.lifecycle-projection.backend=postgresql")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(PostgresSkillLifecycleProjectionStore.class)).isEmpty();
                });
    }

    @Test
    void postgresqlLifecycleProjectionBackendCreatesProjectionStoreAtSchemaVersionThree() {
        lifecycleProjectionReadinessContext
                .withPropertyValues(
                        "skill-center.persistence.backend=postgresql",
                        "skill-center.lifecycle-projection.backend=postgresql",
                        "test.lifecycle.schema-version=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PersistenceBackend.class);
                    assertThat(context.getBean(PersistenceBackend.class).status().schemaVersion()).isEqualTo("3");
                    assertThat(context.getBeansOfType(PostgresSkillLifecycleProjectionStore.class))
                            .hasSize(1);
                });
    }

    @Test
    void lifecycleProjectionMigrationDefinesTheSeededMetaTableAndForeignKeys() throws IOException {
        String migration = new ClassPathResource("db/migration/V2__create_skill_lifecycle_projection.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("create table skill_lifecycle_projection_meta")
                .contains("create table skill_lifecycle_skill_projection")
                .contains("create table skill_lifecycle_version_projection")
                .contains("create table skill_lifecycle_release_projection")
                .contains("create table skill_lifecycle_scope_projection")
                .contains("create table skill_lifecycle_relation_projection")
                .contains("foreign key")
                .contains("revision bigint not null")
                .contains("create index skill_lifecycle_release_projection_lookup_idx")
                .contains("(skill_id, version, target_environment, release_id)")
                .contains("insert into skill_lifecycle_projection_meta");
    }

    @Test
    void postgresqlInfrastructureIsEnabledOnlyByTheNormalizedExplicitBackend() {
        persistenceContext
                .withPropertyValues("skill-center.persistence.backend= PostgreSQL ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PostgresPersistenceBackend.class);
                    assertThat(context.getBeansOfType(PersistenceBackend.class))
                            .hasSize(1)
                            .extractingByKey("postgresPersistenceBackend")
                            .isInstanceOf(PostgresPersistenceBackend.class);
                });
    }

    @Test
    void validShapedPostgresqlConfigurationCreatesOnlyTheExplicitInfrastructureBeans() {
        persistenceContext
                .withPropertyValues(
                        "skill-center.persistence.backend=postgresql",
                        "skill-center.persistence.postgresql.url=jdbc:postgresql://127.0.0.1:1/skill_center",
                        "skill-center.persistence.postgresql.username=skill_center",
                        "skill-center.persistence.postgresql.password=secret",
                        "skill-center.persistence.postgresql.timeout=250ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).hasSingleBean(JdbcTemplate.class);
                    assertThat(context).hasSingleBean(DataSourceTransactionManager.class);
                    assertThat(context).hasSingleBean(Flyway.class);
                });
    }

    @Test
    void missingPostgresqlConnectionConfigurationReportsARedactedFailClosedStatus() {
        persistenceContext
                .withPropertyValues("skill-center.persistence.backend=postgresql")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    PersistenceBackendStatus status = context.getBean(PostgresPersistenceBackend.class).status();

                    assertThat(status).isEqualTo(PersistenceBackendStatus.failClosed(
                            "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", null, null));
                    assertThat(status.toString()).doesNotContain("jdbc:", "password", "username", "secret");
                });
    }

    @Test
    void subMinimumPostgresqlTimeoutFailsClosedBeforeHikariCanConstructADatasource() {
        postgresContextWithTimeout("1ms").run(context -> assertRedactedInvalidTimeout(context));
    }

    @Test
    void overflowLikePostgresqlTimeoutFailsClosedBeforeHikariCanConstructADatasource() {
        postgresContextWithTimeout("P110000000000D").run(context -> assertRedactedInvalidTimeout(context));
    }

    @Test
    void malformedPostgresqlTimeoutFailsClosedBeforeBindingCanConstructADatasource() {
        postgresContextWithTimeout("not-a-duration").run(context -> assertRedactedInvalidTimeout(context));
    }

    private ApplicationContextRunner postgresContextWithTimeout(String timeout) {
        return persistenceContext.withPropertyValues(
                "skill-center.persistence.backend=postgresql",
                "skill-center.persistence.postgresql.url=jdbc:postgresql://127.0.0.1:1/skill_center",
                "skill-center.persistence.postgresql.username=skill_center",
                "skill-center.persistence.postgresql.password=secret",
                "skill-center.persistence.postgresql.timeout=" + timeout);
    }

    private void assertRedactedInvalidTimeout(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();

        PersistenceBackendStatus status = context.getBean(PostgresPersistenceBackend.class).status();

        assertThat(status).isEqualTo(PersistenceBackendStatus.failClosed(
                "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", null, null));
        assertThat(status.toString()).doesNotContain("jdbc:", "password", "username", "secret");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({QualityEvidenceStore.class, JdbcQualityEvidenceStore.class})
    static class QualityEvidenceRepositoryConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PersistenceBackendFixtureConfiguration {
        @Bean
        PersistenceArtifactCatalog persistenceArtifactCatalog(PersistenceControlProperties properties,
                                                               @Value("${skill-center.persistence.configured-root:${user.dir}}") String configuredRoot) {
            return new PersistenceArtifactCatalog(properties, Path.of(configuredRoot));
        }

    }

    @Configuration(proxyBeanMethods = false)
    static class ReleaseRepositoryFixtureConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LifecycleProjectionSchemaOneFixtureConfiguration {
        @Bean
        DataSource postgresDataSource() {
            return new AbstractDataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    throw new SQLException("test fixture does not open connections");
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    throw new SQLException("test fixture does not open connections");
                }
            };
        }

        @Bean
        JdbcTemplate postgresJdbcTemplate(DataSource postgresDataSource) {
            return new JdbcTemplate(postgresDataSource);
        }

        @Bean
        DataSourceTransactionManager postgresTransactionManager(DataSource postgresDataSource) {
            return new DataSourceTransactionManager(postgresDataSource);
        }

        @Bean
        PersistenceBackend postgresPersistenceBackend(
                @Value("${test.lifecycle.schema-version:2}") String schemaVersion) {
            return new PersistenceBackend() {
                @Override
                public String backendId() {
                    return "postgresql";
                }

                @Override
                public PersistenceBackendStatus status() {
                    return PersistenceBackendStatus.ready("postgresql", schemaVersion, 7L);
                }
            };
        }
    }
}
