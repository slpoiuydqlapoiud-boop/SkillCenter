package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.stereotype.Service;

/** Evaluates whether Benchmark evidence is backed by the selected migrated shared store. */
@Service
public class BenchmarkBackendReadinessService implements BenchmarkBackendHealth {
    private static final int POSTGRES_REQUIRED_SCHEMA_VERSION = 13;
    private static final int MYSQL_REQUIRED_SCHEMA_VERSION = 2;
    private final PersistenceControlProperties properties;
    private final PersistenceBackend persistence;

    public BenchmarkBackendReadinessService(PersistenceControlProperties properties,
                                            PersistenceBackend persistence) {
        this.properties = require(properties, "properties");
        this.persistence = require(persistence, "persistence");
    }

    @Override
    public BenchmarkBackendReadiness readiness() {
        try {
            String selected = PersistenceControlProperties.normalizeBackendValue(properties.normalizedBenchmarkBackend());
            if ("json".equals(selected)) {
                return new BenchmarkBackendReadiness("json", "DEGRADED", "BENCHMARK_JSON_ONLY",
                        "Benchmark 仅使用本地 JSON，不支持多实例共享");
            }
            if (!"postgresql".equals(selected) && !"mysql".equals(selected)) {
                return notReady(selected.isBlank() ? "unknown" : selected,
                        "BENCHMARK_BACKEND_INVALID", "Benchmark 持久化后端配置无效");
            }
            String global = PersistenceControlProperties.normalizeBackendValue(properties.normalizedBackend());
            if (!selected.equals(global)) {
                if ("mysql".equals(selected)) {
                    return notReady("mysql", "BENCHMARK_MYSQL_REQUIRES_GLOBAL_MYSQL",
                            "Benchmark MySQL 后端要求全局 MySQL 持久化");
                }
                return notReady("postgresql", "BENCHMARK_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "Benchmark PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            PersistenceBackendStatus status = persistence.status();
            if (status == null || !"READY".equals(status.state())
                    || !selected.equals(PersistenceControlProperties.normalizeBackendValue(status.backendId()))) {
                return notReady(selected, "BENCHMARK_PERSISTENCE_NOT_READY",
                        "全局 " + displayName(selected) + " 持久化控制面尚未就绪");
            }
            if (!hasRequiredSchema(status.schemaVersion(), selected)) {
                return notReady(selected, "BENCHMARK_SCHEMA_REQUIRED",
                        "Benchmark " + displayName(selected) + " V" + requiredSchemaVersion(selected)
                                + " schema 尚未就绪");
            }
            String reason = "mysql".equals(selected) ? "BENCHMARK_MYSQL_READY" : "BENCHMARK_POSTGRES_READY";
            return new BenchmarkBackendReadiness(selected, "READY", reason,
                    "Benchmark " + displayName(selected) + " 存储已就绪");
        } catch (RuntimeException exception) {
            return notReady("unknown", "BENCHMARK_READINESS_UNAVAILABLE", "Benchmark 持久化状态不可用");
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

    private int requiredSchemaVersion(String backend) {
        return "mysql".equals(backend) ? MYSQL_REQUIRED_SCHEMA_VERSION : POSTGRES_REQUIRED_SCHEMA_VERSION;
    }

    private String displayName(String backend) {
        return "postgresql".equals(backend) ? "PostgreSQL" : "MySQL";
    }

    private BenchmarkBackendReadiness notReady(String backend, String reason, String summary) {
        return new BenchmarkBackendReadiness(backend, "NOT_READY", reason, summary);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
