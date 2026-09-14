package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenchmarkBackendReadinessServiceTest {
    @Test
    void jsonBackendIsDegradedForMultiInstanceAutomation() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedBenchmarkBackend()).thenReturn("json");

        assertThat(new BenchmarkBackendReadinessService(properties, persistence).readiness())
                .isEqualTo(new BenchmarkBackendReadiness("json", "DEGRADED", "BENCHMARK_JSON_ONLY",
                        "Benchmark 仅使用本地 JSON，不支持多实例共享"));
    }

    @Test
    void postgresqlBackendIsReadyOnlyAfterV13Schema() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedBenchmarkBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "13", null));

        assertThat(new BenchmarkBackendReadinessService(properties, persistence).readiness())
                .isEqualTo(new BenchmarkBackendReadiness("postgresql", "READY", "BENCHMARK_POSTGRES_READY",
                        "Benchmark PostgreSQL 存储已就绪"));
    }

    @Test
    void mysqlBackendIsReadyAfterDepartmentDocumentSchemaIsMigrated() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedBenchmarkBackend()).thenReturn("mysql");
        when(properties.normalizedBackend()).thenReturn("mysql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("mysql", "2", null));

        assertThat(new BenchmarkBackendReadinessService(properties, persistence).readiness())
                .isEqualTo(new BenchmarkBackendReadiness("mysql", "READY", "BENCHMARK_MYSQL_READY",
                        "Benchmark MySQL 存储已就绪"));
    }

    @Test
    void postgresqlBackendFailsClosedWhenV13IsMissing() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedBenchmarkBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "12", null));

        BenchmarkBackendReadiness readiness = new BenchmarkBackendReadinessService(properties, persistence).readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("BENCHMARK_SCHEMA_REQUIRED");
    }
}
