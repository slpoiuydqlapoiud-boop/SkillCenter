package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.stereotype.Service;

/** Evaluates whether the shared Skill scope and relation stores are production-ready. */
@Service
public class SkillAssetBackendReadinessService implements SkillAssetBackendHealth {
    private static final int REQUIRED_SCHEMA_VERSION = 15;
    private final PersistenceControlProperties properties;
    private final PersistenceBackend persistence;

    public SkillAssetBackendReadinessService(PersistenceControlProperties properties,
                                             PersistenceBackend persistence) {
        this.properties = require(properties, "properties");
        this.persistence = require(persistence, "persistence");
    }

    @Override
    public SkillAssetBackendReadiness readiness() {
        try {
            String scope = PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedSkillScopeBackend());
            String relation = PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedSkillRelationBackend());
            if (!isSupported(scope) || !isSupported(relation)) {
                return notReady("unknown", "SKILL_ASSET_BACKEND_INVALID", "Skill 资产持久化后端配置无效");
            }
            if ("json".equals(scope) && "json".equals(relation)) {
                return new SkillAssetBackendReadiness("json", "DEGRADED", "SKILL_ASSET_JSON_ONLY",
                        "Skill scope 与 relation 仅使用本地 JSON，不支持多实例共享");
            }
            if (!"postgresql".equals(PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedBackend()))) {
                return notReady("postgresql", "SKILL_ASSET_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "Skill 资产 PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            PersistenceBackendStatus status = persistence.status();
            if (status == null || !"READY".equals(status.state())
                    || !"postgresql".equals(status.backendId())) {
                return notReady("postgresql", "SKILL_ASSET_PERSISTENCE_NOT_READY",
                        "全局 PostgreSQL 持久化控制面尚未就绪");
            }
            if (!hasRequiredSchema(status.schemaVersion())) {
                return notReady("postgresql", "SKILL_ASSET_SCHEMA_REQUIRED",
                        "Skill scope/relation PostgreSQL V14/V15 schema 尚未就绪");
            }
            if (!scope.equals(relation)) {
                return new SkillAssetBackendReadiness("mixed", "DEGRADED", "SKILL_ASSET_MIXED_BACKENDS",
                        "Skill scope 与 relation 使用不同持久化后端");
            }
            return new SkillAssetBackendReadiness("postgresql", "READY", "SKILL_ASSET_POSTGRES_READY",
                    "Skill scope 与 relation PostgreSQL 存储已就绪");
        } catch (RuntimeException exception) {
            return notReady("unknown", "SKILL_ASSET_READINESS_UNAVAILABLE", "Skill 资产持久化状态不可用");
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

    private boolean isSupported(String backend) {
        return "json".equals(backend) || "postgresql".equals(backend);
    }

    private SkillAssetBackendReadiness notReady(String backend, String reason, String summary) {
        return new SkillAssetBackendReadiness(backend, "NOT_READY", reason, summary);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
