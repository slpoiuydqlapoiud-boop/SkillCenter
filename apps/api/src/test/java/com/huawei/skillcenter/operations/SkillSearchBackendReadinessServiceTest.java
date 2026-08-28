package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import com.huawei.skillcenter.search.SkillSearchIndex;
import com.huawei.skillcenter.search.SkillSearchIndexStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillSearchBackendReadinessServiceTest {
    @Test
    void jsonBackendIsDegradedForMultiInstanceSearch() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        SkillSearchIndex index = mock(SkillSearchIndex.class);
        when(properties.normalizedSearchIndexBackend()).thenReturn("json");

        assertThat(new SkillSearchBackendReadinessService(properties, persistence, index).readiness())
                .isEqualTo(new SkillSearchBackendReadiness("json", "DEGRADED", "SEARCH_INDEX_JSON_ONLY",
                        "Skill 搜索索引仅使用本地 JSON，不支持多实例共享"));
    }

    @Test
    void postgresqlBackendRequiresV16AndAReadyIndex() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        SkillSearchIndex index = mock(SkillSearchIndex.class);
        when(properties.normalizedSearchIndexBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "16", null));
        when(index.status()).thenReturn(new SkillSearchIndexStatus("READY", 3, 4, "hash", "3"));

        assertThat(new SkillSearchBackendReadinessService(properties, persistence, index).readiness())
                .isEqualTo(new SkillSearchBackendReadiness("postgresql", "READY", "SEARCH_INDEX_POSTGRES_READY",
                        "Skill 搜索索引 PostgreSQL 存储已就绪"));
    }

    @Test
    void postgresqlBackendFailsClosedWhenIndexIsStale() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        SkillSearchIndex index = mock(SkillSearchIndex.class);
        when(properties.normalizedSearchIndexBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "16", null));
        when(index.status()).thenReturn(new SkillSearchIndexStatus("STALE", 3, 4, "hash", "3"));

        assertThat(new SkillSearchBackendReadinessService(properties, persistence, index).readiness())
                .isEqualTo(new SkillSearchBackendReadiness("postgresql", "NOT_READY", "SEARCH_INDEX_NOT_READY",
                        "Skill 搜索索引尚未完成最新投影"));
    }

    @Test
    void enabledCrossInstanceRefreshRequiresV18Schema() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        SkillSearchIndex index = mock(SkillSearchIndex.class);
        when(properties.normalizedSearchIndexBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(properties.searchIndexEventsEnabled()).thenReturn(true);
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "17", null));

        SkillSearchBackendReadiness readiness = new SkillSearchBackendReadinessService(properties, persistence, index)
                .readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("SEARCH_INDEX_EVENTS_SCHEMA_REQUIRED");
    }

    @Test
    void enabledRefreshRetentionRequiresV19Schema() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        SkillSearchIndex index = mock(SkillSearchIndex.class);
        when(properties.normalizedSearchIndexBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(properties.searchIndexEventsEnabled()).thenReturn(true);
        when(properties.searchIndexEventRetentionSchedulerEnabled()).thenReturn(true);
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "18", null));

        SkillSearchBackendReadiness readiness = new SkillSearchBackendReadinessService(properties, persistence, index)
                .readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("SEARCH_INDEX_EVENTS_RETENTION_SCHEMA_REQUIRED");
    }
}
