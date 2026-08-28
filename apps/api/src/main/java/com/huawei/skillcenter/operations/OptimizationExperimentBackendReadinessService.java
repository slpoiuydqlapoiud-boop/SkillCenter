package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.stereotype.Service;

/**
 * Evaluates whether optimization experiment evidence has a shared, migrated backend.
 * The service is read-only and never falls back between JSON and PostgreSQL.
 */
@Service
public class OptimizationExperimentBackendReadinessService implements OptimizationExperimentBackendHealth {
    private static final int REQUIRED_SCHEMA_VERSION = 12;
    private final PersistenceControlProperties properties;
    private final PersistenceBackend persistence;

    public OptimizationExperimentBackendReadinessService(PersistenceControlProperties properties,
                                                          PersistenceBackend persistence) {
        this.properties = require(properties, "properties");
        this.persistence = require(persistence, "persistence");
    }

    @Override
    public OptimizationExperimentBackendReadiness readiness() {
        try {
            String selected = PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedOptimizationExperimentBackend());
            if ("json".equals(selected)) {
                return new OptimizationExperimentBackendReadiness(
                        "json", "DEGRADED", "OPTIMIZATION_EXPERIMENT_JSON_ONLY",
                        "优化实验持久化仅使用本地 JSON，不支持多实例共享");
            }
            if (!"postgresql".equals(selected)) {
                return notReady(selected.isBlank() ? "unknown" : selected,
                        "OPTIMIZATION_EXPERIMENT_BACKEND_INVALID", "优化实验持久化后端配置无效");
            }
            if (!"postgresql".equals(PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedBackend()))) {
                return notReady("postgresql", "OPTIMIZATION_EXPERIMENT_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "优化实验 PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            PersistenceBackendStatus status = persistence.status();
            if (status == null || !"READY".equals(status.state())
                    || !"postgresql".equals(status.backendId())) {
                return notReady("postgresql", "OPTIMIZATION_EXPERIMENT_PERSISTENCE_NOT_READY",
                        "全局 PostgreSQL 持久化控制面尚未就绪");
            }
            if (!hasRequiredSchema(status.schemaVersion())) {
                return notReady("postgresql", "OPTIMIZATION_EXPERIMENT_SCHEMA_REQUIRED",
                        "优化实验 PostgreSQL V12 schema 尚未就绪");
            }
            return new OptimizationExperimentBackendReadiness(
                    "postgresql", "READY", "OPTIMIZATION_EXPERIMENT_POSTGRES_READY",
                    "优化实验 PostgreSQL 存储已就绪");
        } catch (RuntimeException exception) {
            return notReady("unknown", "OPTIMIZATION_EXPERIMENT_READINESS_UNAVAILABLE",
                    "优化实验持久化状态不可用");
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

    private OptimizationExperimentBackendReadiness notReady(String backend, String reason, String summary) {
        return new OptimizationExperimentBackendReadiness(backend, "NOT_READY", reason, summary);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
