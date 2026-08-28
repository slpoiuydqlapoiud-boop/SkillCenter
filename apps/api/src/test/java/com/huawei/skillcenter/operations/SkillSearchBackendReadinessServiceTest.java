package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import com.huawei.skillcenter.search.SkillSearchIndex;
import com.huawei.skillcenter.search.SkillSearchIndexStatus;
import com.huawei.skillcenter.search.SkillSearchRebuildResult;
import com.huawei.skillcenter.search.SkillSearchRemoteHealth;
import com.huawei.skillcenter.search.SkillSearchProbeResult;
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
    void opensearchBackendRequiresFreshReachableProbe() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        ProbeAwareIndex index = new ProbeAwareIndex(false);
        when(properties.normalizedSearchIndexBackend()).thenReturn("opensearch");

        assertThat(new SkillSearchBackendReadinessService(properties, persistence, index).readiness())
                .isEqualTo(new SkillSearchBackendReadiness("opensearch", "NOT_READY",
                        "SEARCH_INDEX_PROBE_REQUIRED", "外部 Skill 搜索索引尚未完成新鲜连通性探测"));
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
    void enabledRefreshRetentionRequiresV20Schema() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        SkillSearchIndex index = mock(SkillSearchIndex.class);
        when(properties.normalizedSearchIndexBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(properties.searchIndexEventsEnabled()).thenReturn(true);
        when(properties.searchIndexEventRetentionSchedulerEnabled()).thenReturn(true);
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "19", null));

        SkillSearchBackendReadiness readiness = new SkillSearchBackendReadinessService(properties, persistence, index)
                .readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("SEARCH_INDEX_EVENTS_RETENTION_SCHEMA_REQUIRED");
    }

    @Test
    void enabledCrossInstanceRefreshRequiresV20ConsumerLifecycleSchema() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        SkillSearchIndex index = mock(SkillSearchIndex.class);
        when(properties.normalizedSearchIndexBackend()).thenReturn("postgresql");
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(properties.searchIndexEventsEnabled()).thenReturn(true);
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "19", null));

        SkillSearchBackendReadiness readiness = new SkillSearchBackendReadinessService(properties, persistence, index)
                .readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("SEARCH_INDEX_EVENTS_SCHEMA_REQUIRED");
    }

    private static final class ProbeAwareIndex implements SkillSearchIndex, SkillSearchRemoteHealth {
        private final boolean fresh;

        private ProbeAwareIndex(boolean fresh) {
            this.fresh = fresh;
        }

        @Override
        public String backend() {
            return "opensearch";
        }

        @Override
        public SkillSearchIndexStatus status() {
            return new SkillSearchIndexStatus("READY", 1, 1, "hash", "1");
        }

        @Override
        public SkillSearchRebuildResult rebuild(java.util.List<com.huawei.skillcenter.search.SkillSearchDocument> documents,
                                                String sourceHash) {
            return new SkillSearchRebuildResult(1, documents.size(), sourceHash, "1");
        }

        @Override
        public void invalidate(String reason) {
        }

        @Override
        public java.util.List<com.huawei.skillcenter.search.SkillSearchHit> search(
                com.huawei.skillcenter.search.SkillSearchQuery query) {
            return java.util.List.of();
        }

        @Override
        public SkillSearchProbeResult probe() {
            return new SkillSearchProbeResult("opensearch", fresh ? "REACHABLE" : "UNREACHABLE",
                    fresh ? "SEARCH_INDEX_PROBE_OK" : "SEARCH_INDEX_PROBE_FAILED", null, 1, java.time.Instant.now());
        }

        @Override
        public boolean probeFresh() {
            return fresh;
        }
    }
}
