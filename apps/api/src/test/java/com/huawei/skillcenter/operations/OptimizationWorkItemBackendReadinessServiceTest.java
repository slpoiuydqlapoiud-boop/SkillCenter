package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptimizationWorkItemBackendReadinessServiceTest {
    @Test
    void jsonBackendIsExplicitlyReportedAsDegradedForMultiInstanceReadiness() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationWorkItemBackend()).thenReturn("json");

        OptimizationWorkItemBackendReadiness readiness = service(properties, persistence).readiness();

        assertThat(readiness.backend()).isEqualTo("json");
        assertThat(readiness.status()).isEqualTo("DEGRADED");
        assertThat(readiness.reasonCode()).isEqualTo("OPTIMIZATION_WORK_ITEM_JSON_ONLY");
    }

    @Test
    void postgresqlBackendIsReadyOnlyAfterGlobalPersistenceAndV5SchemaAreReady() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationWorkItemBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "5", null));

        OptimizationWorkItemBackendReadiness readiness = service(properties, persistence).readiness();

        assertThat(readiness).isEqualTo(new OptimizationWorkItemBackendReadiness(
                "postgresql", "READY", "OPTIMIZATION_WORK_ITEM_POSTGRES_READY", "优化工作项 PostgreSQL 存储已就绪"));
    }

    @Test
    void mysqlBackendIsReadyAfterDepartmentDocumentSchemaIsMigrated() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationWorkItemBackend()).thenReturn("mysql");
        when(properties.normalizedBackend()).thenReturn("mysql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("mysql", "2", null));

        assertThat(service(properties, persistence).readiness()).isEqualTo(new OptimizationWorkItemBackendReadiness(
                "mysql", "READY", "OPTIMIZATION_WORK_ITEM_MYSQL_READY", "优化工作项 MySQL 存储已就绪"));
    }

    @Test
    void postgresqlBackendFailsClosedWhenGlobalPersistenceIsNotReady() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationWorkItemBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.failClosed(
                "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", null, null));

        OptimizationWorkItemBackendReadiness readiness = service(properties, persistence).readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("OPTIMIZATION_WORK_ITEM_PERSISTENCE_NOT_READY");
    }

    @Test
    void postgresqlBackendFailsClosedWhenV5SchemaIsMissing() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedOptimizationWorkItemBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "4", null));

        OptimizationWorkItemBackendReadiness readiness = service(properties, persistence).readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("OPTIMIZATION_WORK_ITEM_SCHEMA_REQUIRED");
    }

    private OptimizationWorkItemBackendReadinessService service(PersistenceControlProperties properties,
                                                                 PersistenceBackend persistence) {
        return new OptimizationWorkItemBackendReadinessService(properties, persistence);
    }
}
