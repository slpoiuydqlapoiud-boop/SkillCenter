package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.stereotype.Service;

/** Evaluates whether the shared Skill scope and relation stores are production-ready. */
@Service
public class SkillAssetBackendReadinessService implements SkillAssetBackendHealth {
    private static final int POSTGRES_REQUIRED_SCHEMA_VERSION = 15;
    private static final int MYSQL_REQUIRED_SCHEMA_VERSION = 2;
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
            String global = PersistenceControlProperties.normalizeBackendValue(properties.normalizedBackend());
            if (("postgresql".equals(scope) || "postgresql".equals(relation))
                    && !"postgresql".equals(global)) {
                return notReady("postgresql", "SKILL_ASSET_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "Skill 资产 PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            if (!scope.equals(relation)) {
                return new SkillAssetBackendReadiness("mixed", "DEGRADED", "SKILL_ASSET_MIXED_BACKENDS",
                        "Skill scope 与 relation 使用不同持久化后端");
            }
            if (!"postgresql".equals(scope) && !"mysql".equals(scope)) {
                return notReady("unknown", "SKILL_ASSET_BACKEND_INVALID", "Skill 资产持久化后端配置无效");
            }
            if (!scope.equals(global)) {
                if ("mysql".equals(scope)) {
                    return notReady("mysql", "SKILL_ASSET_MYSQL_REQUIRES_GLOBAL_MYSQL",
                            "Skill 资产 MySQL 后端要求全局 MySQL 持久化");
                }
                return notReady("postgresql", "SKILL_ASSET_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "Skill 资产 PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            PersistenceBackendStatus status = persistence.status();
            if (status == null || !"READY".equals(status.state())
                    || !scope.equals(PersistenceControlProperties.normalizeBackendValue(status.backendId()))) {
                return notReady(scope, "SKILL_ASSET_PERSISTENCE_NOT_READY",
                        "全局 " + displayName(scope) + " 持久化控制面尚未就绪");
            }
            if (!hasRequiredSchema(status.schemaVersion(), scope)) {
                return notReady(scope, "SKILL_ASSET_SCHEMA_REQUIRED",
                        "Skill scope/relation " + displayName(scope) + " V"
                                + requiredSchemaVersion(scope) + " schema 尚未就绪");
            }
            String reason = "mysql".equals(scope) ? "SKILL_ASSET_MYSQL_READY" : "SKILL_ASSET_POSTGRES_READY";
            return new SkillAssetBackendReadiness(scope, "READY", reason,
                    "Skill scope 与 relation " + displayName(scope) + " 存储已就绪");
        } catch (RuntimeException exception) {
            return notReady("unknown", "SKILL_ASSET_READINESS_UNAVAILABLE", "Skill 资产持久化状态不可用");
        }
    }

    private boolean hasRequiredSchema(String schemaVersion, String backend) {
        if (schemaVersion == null || schemaVersion.isBlank()) return false;
        try {
            return Integer.parseInt(schemaVersion.trim().split("\\.", 2)[0]) >= requiredSchemaVersion(backend);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isSupported(String backend) {
        return "json".equals(backend) || "postgresql".equals(backend) || "mysql".equals(backend);
    }

    private int requiredSchemaVersion(String backend) {
        return "mysql".equals(backend) ? MYSQL_REQUIRED_SCHEMA_VERSION : POSTGRES_REQUIRED_SCHEMA_VERSION;
    }

    private String displayName(String backend) {
        return "postgresql".equals(backend) ? "PostgreSQL" : "MySQL";
    }

    private SkillAssetBackendReadiness notReady(String backend, String reason, String summary) {
        return new SkillAssetBackendReadiness(backend, "NOT_READY", reason, summary);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
