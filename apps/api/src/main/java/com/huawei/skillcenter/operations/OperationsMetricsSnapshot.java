package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.Map;

public record OperationsMetricsSnapshot(
        OperationsWindow window,
        Instant generatedAt,
        Health health,
        RequestCounts requests,
        LatencyMetrics latency,
        Map<String, Long> securityEvents) {

    public record Health(String status, String packageStorage, String invocationPersistence, String metricsPersistence) {
    }

    public record RequestCounts(long total, long successes, long clientErrors, long serverErrors) {
    }

    public record LatencyMetrics(long p50Ms, long p95Ms, long maxMs) {
    }
}
