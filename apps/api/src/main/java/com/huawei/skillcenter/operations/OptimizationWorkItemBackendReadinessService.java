package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.stereotype.Service;

/**
 * Evaluates whether optimization work items have a shared, migrated persistence backend.
 * The service never probes or mutates business data and never falls back between backends.
 */
@Service
public class OptimizationWorkItemBackendReadinessService implements OptimizationWorkItemBackendHealth {
    private static final int REQUIRED_SCHEMA_VERSION = 5;
    private final PersistenceControlProperties properties;
    private final PersistenceBackend persistence;

    public OptimizationWorkItemBackendReadinessService(PersistenceControlProperties properties,
                                                       PersistenceBackend persistence) {
        this.properties = require(properties, "properties");
        this.persistence = require(persistence, "persistence");
    }

    @Override
    public OptimizationWorkItemBackendReadiness readiness() {
        try {
            String selected = PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedOptimizationWorkItemBackend());
            if ("json".equals(selected)) {
                return new OptimizationWorkItemBackendReadiness(
                        "json", "DEGRADED", "OPTIMIZATION_WORK_ITEM_JSON_ONLY",
                        "优化工作项仅使用本地 JSON，不支持多实例共享");
            }
            if (!"postgresql".equals(selected)) {
                return notReady(selected.isBlank() ? "unknown" : selected,
                        "OPTIMIZATION_WORK_ITEM_BACKEND_INVALID", "优化工作项后端配置无效");
            }
            if (!"postgresql".equals(PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedBackend()))) {
                return notReady("postgresql", "OPTIMIZATION_WORK_ITEM_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "优化工作项 PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            PersistenceBackendStatus status = persistence.status();
            if (status == null || !"READY".equals(status.state())
                    || !"postgresql".equals(status.backendId())) {
                return notReady("postgresql", "OPTIMIZATION_WORK_ITEM_PERSISTENCE_NOT_READY",
                        "全局 PostgreSQL 持久化控制面尚未就绪");
            }
            if (!hasRequiredSchema(status.schemaVersion())) {
                return notReady("postgresql", "OPTIMIZATION_WORK_ITEM_SCHEMA_REQUIRED",
                        "优化工作项 PostgreSQL V5 schema 尚未就绪");
            }
            return new OptimizationWorkItemBackendReadiness(
                    "postgresql", "READY", "OPTIMIZATION_WORK_ITEM_POSTGRES_READY",
                    "优化工作项 PostgreSQL 存储已就绪");
        } catch (RuntimeException exception) {
            return notReady("unknown", "OPTIMIZATION_WORK_ITEM_READINESS_UNAVAILABLE",
                    "优化工作项持久化状态不可用");
        }
    }

    private boolean hasRequiredSchema(String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) return false;
        try {
            String major = schemaVersion.trim().split("\\.", 2)[0];
            return Integer.parseInt(major) >= REQUIRED_SCHEMA_VERSION;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private OptimizationWorkItemBackendReadiness notReady(String backend, String reason, String summary) {
        return new OptimizationWorkItemBackendReadiness(backend, "NOT_READY", reason, summary);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
