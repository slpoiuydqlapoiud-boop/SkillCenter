package com.huawei.skillcenter.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionEnvironmentPersistenceContractTest {
    @TempDir
    Path tempDir;

    @Test
    void newEnvironmentStartsAtRevisionOneAndStatusChangeAdvancesRevision() {
        ExecutionEnvironment environment = environment("runtime-a", ExecutionEnvironmentKind.AGENT_RUNTIME);

        assertThat(environment.revision()).isEqualTo(1);
        assertThat(environment.withStatus(ExecutionEnvironmentStatus.DEGRADED, "admin",
                Instant.parse("2026-08-25T01:00:00Z")).revision()).isEqualTo(2);
    }

    @Test
    void postgresqlExecutionEnvironmentBackendRequiresGlobalPostgresqlPersistence() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setExecutionEnvironmentBackend("postgresql");

        assertThatThrownBy(() -> properties.validate(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("executionEnvironmentBackend=postgresql requires backend=postgresql");
    }

    @Test
    void migrationDefinesExecutionEnvironmentAssetTableAndRevision() throws Exception {
        String migration = new ClassPathResource("db/migration/V11__create_execution_environments.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE skill_execution_environment")
                .contains("revision integer NOT NULL")
                .contains("PRIMARY KEY (kind, environment_id)");
    }

    @Test
    void jsonRepositoryRejectsStaleRevisionInsteadOfOverwritingNewerStatus() {
        ExecutionEnvironmentStore store = new ExecutionEnvironmentStore(
                tempDir.resolve("execution-environments.json"),
                new ObjectMapper().findAndRegisterModules(),
                java.time.Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), java.time.ZoneOffset.UTC));
        ExecutionEnvironment original = store.find(ExecutionEnvironmentKind.AGENT_RUNTIME, "openclaw").orElseThrow();
        ExecutionEnvironment currentUpdate = original.withStatus(ExecutionEnvironmentStatus.DEGRADED,
                "admin", Instant.parse("2026-08-25T01:00:00Z"));
        store.replace(currentUpdate, original.revision());
        ExecutionEnvironment staleUpdate = original.withStatus(ExecutionEnvironmentStatus.DISABLED,
                "other-admin", Instant.parse("2026-08-25T02:00:00Z"));

        assertThatThrownBy(() -> store.replace(staleUpdate, original.revision()))
                .isInstanceOf(ExecutionEnvironmentRevisionConflictException.class)
                .hasMessage("execution environment revision conflict");
    }

    @Test
    void jdbcRepositoryWritesExecutionEnvironmentPayload() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = transactionManager();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        JdbcExecutionEnvironmentStore store = new JdbcExecutionEnvironmentStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactions);

        ExecutionEnvironment environment = environment("runtime-a", ExecutionEnvironmentKind.AGENT_RUNTIME);

        assertThat(store.create(environment)).isEqualTo(environment);
    }

    @Test
    void jdbcRepositoryDatabaseFailureIsNotReportedAsBusinessConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = transactionManager();
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        JdbcExecutionEnvironmentStore store = new JdbcExecutionEnvironmentStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), transactions);

        assertThatThrownBy(() -> store.create(environment("runtime-a", ExecutionEnvironmentKind.AGENT_RUNTIME)))
                .isInstanceOf(ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException.class);
    }

    private ExecutionEnvironment environment(String id, ExecutionEnvironmentKind kind) {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        return new ExecutionEnvironment(id, kind, "context-v1", ExecutionEnvironmentStatus.ACTIVE,
                List.of("context-only"), "adapter", "secret://runtime/config",
                "admin", now, "admin", now);
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any()))
                .thenReturn(new DefaultTransactionStatus(null, false, false, false, false, null));
        return transactions;
    }
}
