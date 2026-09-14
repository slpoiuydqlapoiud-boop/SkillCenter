package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillAssetBackendReadinessServiceTest {
    @Test
    void jsonBackendsAreDegradedForMultiInstanceAutomation() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedSkillScopeBackend()).thenReturn("json");
        when(properties.normalizedSkillRelationBackend()).thenReturn("json");

        assertThat(new SkillAssetBackendReadinessService(properties, persistence).readiness())
                .isEqualTo(new SkillAssetBackendReadiness("json", "DEGRADED", "SKILL_ASSET_JSON_ONLY",
                        "Skill scope 与 relation 仅使用本地 JSON，不支持多实例共享"));
    }

    @Test
    void postgresqlBackendsAreReadyOnlyAfterV15Schema() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedSkillScopeBackend()).thenReturn("postgresql");
        when(properties.normalizedSkillRelationBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "15", null));

        assertThat(new SkillAssetBackendReadinessService(properties, persistence).readiness())
                .isEqualTo(new SkillAssetBackendReadiness("postgresql", "READY", "SKILL_ASSET_POSTGRES_READY",
                        "Skill scope 与 relation PostgreSQL 存储已就绪"));
    }

    @Test
    void mysqlBackendsAreReadyAfterDepartmentDocumentSchemaIsMigrated() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedSkillScopeBackend()).thenReturn("mysql");
        when(properties.normalizedSkillRelationBackend()).thenReturn("mysql");
        when(properties.normalizedBackend()).thenReturn("mysql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("mysql", "2", null));

        assertThat(new SkillAssetBackendReadinessService(properties, persistence).readiness())
                .isEqualTo(new SkillAssetBackendReadiness("mysql", "READY", "SKILL_ASSET_MYSQL_READY",
                        "Skill scope 与 relation MySQL 存储已就绪"));
    }

    @Test
    void postgresqlBackendsFailClosedWhenGlobalPersistenceIsNotPostgresql() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedSkillScopeBackend()).thenReturn("postgresql");
        when(properties.normalizedSkillRelationBackend()).thenReturn("json");
        when(properties.normalizedBackend()).thenReturn("json");

        SkillAssetBackendReadiness readiness = new SkillAssetBackendReadinessService(properties, persistence).readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("SKILL_ASSET_POSTGRES_REQUIRES_GLOBAL_POSTGRES");
    }

    @Test
    void postgresqlBackendsFailClosedWhenV15SchemaIsMissing() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(properties.normalizedSkillScopeBackend()).thenReturn("postgresql");
        when(properties.normalizedSkillRelationBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "14", null));

        SkillAssetBackendReadiness readiness = new SkillAssetBackendReadinessService(properties, persistence).readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("SKILL_ASSET_SCHEMA_REQUIRED");
    }
}
