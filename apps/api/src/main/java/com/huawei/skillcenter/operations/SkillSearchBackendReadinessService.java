package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import com.huawei.skillcenter.search.SkillSearchIndex;
import com.huawei.skillcenter.search.SkillSearchIndexStatus;
import org.springframework.stereotype.Service;

/** Evaluates shared PostgreSQL schema and current search projection readiness. */
@Service
public class SkillSearchBackendReadinessService implements SkillSearchBackendHealth {
    private static final int REQUIRED_SCHEMA_VERSION = 16;
    private final PersistenceControlProperties properties;
    private final PersistenceBackend persistence;
    private final SkillSearchIndex index;

    public SkillSearchBackendReadinessService(PersistenceControlProperties properties,
                                              PersistenceBackend persistence,
                                              SkillSearchIndex index) {
        this.properties = require(properties, "properties");
        this.persistence = require(persistence, "persistence");
        this.index = require(index, "index");
    }

    @Override
    public SkillSearchBackendReadiness readiness() {
        try {
            String selected = PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedSearchIndexBackend());
            if ("json".equals(selected)) {
                return new SkillSearchBackendReadiness("json", "DEGRADED", "SEARCH_INDEX_JSON_ONLY",
                        "Skill 搜索索引仅使用本地 JSON，不支持多实例共享");
            }
            if (!"postgresql".equals(selected)) {
                return notReady(selected.isBlank() ? "unknown" : selected,
                        "SEARCH_INDEX_BACKEND_INVALID", "Skill 搜索索引后端配置无效");
            }
            if (!"postgresql".equals(PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedBackend()))) {
                return notReady("postgresql", "SEARCH_INDEX_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "Skill 搜索索引 PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            PersistenceBackendStatus persistenceStatus = persistence.status();
            if (persistenceStatus == null || !"READY".equals(persistenceStatus.state())
                    || !"postgresql".equals(persistenceStatus.backendId())) {
                return notReady("postgresql", "SEARCH_INDEX_PERSISTENCE_NOT_READY",
                        "全局 PostgreSQL 持久化控制面尚未就绪");
            }
            if (!hasRequiredSchema(persistenceStatus.schemaVersion())) {
                return notReady("postgresql", "SEARCH_INDEX_SCHEMA_REQUIRED",
                        "Skill 搜索索引 PostgreSQL V16 schema 尚未就绪");
            }
            SkillSearchIndexStatus indexStatus = index.status();
            if (indexStatus == null || !"READY".equals(indexStatus.state())) {
                return notReady("postgresql", "SEARCH_INDEX_NOT_READY",
                        "Skill 搜索索引尚未完成最新投影");
            }
            return new SkillSearchBackendReadiness("postgresql", "READY", "SEARCH_INDEX_POSTGRES_READY",
                    "Skill 搜索索引 PostgreSQL 存储已就绪");
        } catch (RuntimeException exception) {
            return notReady("unknown", "SEARCH_INDEX_READINESS_UNAVAILABLE", "Skill 搜索索引状态不可用");
        }
    }

    private boolean hasRequiredSchema(String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) return false;
        try {
            return Integer.parseInt(schemaVersion.trim().split("\\.", 2)[0]) >= REQUIRED_SCHEMA_VERSION;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private SkillSearchBackendReadiness notReady(String backend, String reason, String summary) {
        return new SkillSearchBackendReadiness(backend, "NOT_READY", reason, summary);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
