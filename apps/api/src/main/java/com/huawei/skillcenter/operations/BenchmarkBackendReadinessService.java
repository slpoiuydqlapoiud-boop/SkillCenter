package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.stereotype.Service;

/** Evaluates whether Benchmark evidence is backed by the migrated shared PostgreSQL store. */
@Service
public class BenchmarkBackendReadinessService implements BenchmarkBackendHealth {
    private static final int REQUIRED_SCHEMA_VERSION = 13;
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
            if (!"postgresql".equals(selected)) {
                return notReady(selected.isBlank() ? "unknown" : selected,
                        "BENCHMARK_BACKEND_INVALID", "Benchmark 持久化后端配置无效");
            }
            if (!"postgresql".equals(PersistenceControlProperties.normalizeBackendValue(
                    properties.normalizedBackend()))) {
                return notReady("postgresql", "BENCHMARK_POSTGRES_REQUIRES_GLOBAL_POSTGRES",
                        "Benchmark PostgreSQL 后端要求全局 PostgreSQL 持久化");
            }
            PersistenceBackendStatus status = persistence.status();
            if (status == null || !"READY".equals(status.state())
                    || !"postgresql".equals(status.backendId())) {
                return notReady("postgresql", "BENCHMARK_PERSISTENCE_NOT_READY",
                        "全局 PostgreSQL 持久化控制面尚未就绪");
            }
            if (!hasRequiredSchema(status.schemaVersion())) {
                return notReady("postgresql", "BENCHMARK_SCHEMA_REQUIRED",
                        "Benchmark PostgreSQL V13 schema 尚未就绪");
            }
            return new BenchmarkBackendReadiness("postgresql", "READY", "BENCHMARK_POSTGRES_READY",
                    "Benchmark PostgreSQL 存储已就绪");
        } catch (RuntimeException exception) {
            return notReady("unknown", "BENCHMARK_READINESS_UNAVAILABLE", "Benchmark 持久化状态不可用");
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

    private BenchmarkBackendReadiness notReady(String backend, String reason, String summary) {
        return new BenchmarkBackendReadiness(backend, "NOT_READY", reason, summary);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
