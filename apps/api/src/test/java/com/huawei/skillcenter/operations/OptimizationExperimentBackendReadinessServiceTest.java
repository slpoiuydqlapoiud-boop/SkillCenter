package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptimizationExperimentBackendReadinessServiceTest {
    @Test
    void jsonBackendIsExplicitlyReportedAsDegradedForMultiInstanceReadiness() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationExperimentBackend()).thenReturn("json");

        OptimizationExperimentBackendReadiness readiness = service(properties, persistence).readiness();

        assertThat(readiness).isEqualTo(new OptimizationExperimentBackendReadiness(
                "json", "DEGRADED", "OPTIMIZATION_EXPERIMENT_JSON_ONLY",
                "优化实验持久化仅使用本地 JSON，不支持多实例共享"));
    }

    @Test
    void postgresqlBackendIsReadyOnlyAfterGlobalPersistenceAndV12SchemaAreReady() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationExperimentBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "12", null));

        OptimizationExperimentBackendReadiness readiness = service(properties, persistence).readiness();

        assertThat(readiness).isEqualTo(new OptimizationExperimentBackendReadiness(
                "postgresql", "READY", "OPTIMIZATION_EXPERIMENT_POSTGRES_READY",
                "优化实验 PostgreSQL 存储已就绪"));
    }

    @Test
    void postgresqlBackendFailsClosedWhenV12SchemaIsMissing() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationExperimentBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "11", null));

        OptimizationExperimentBackendReadiness readiness = service(properties, persistence).readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("OPTIMIZATION_EXPERIMENT_SCHEMA_REQUIRED");
    }

    private OptimizationExperimentBackendReadinessService service(PersistenceControlProperties properties,
                                                                   PersistenceBackend persistence) {
        return new OptimizationExperimentBackendReadinessService(properties, persistence);
    }
}
