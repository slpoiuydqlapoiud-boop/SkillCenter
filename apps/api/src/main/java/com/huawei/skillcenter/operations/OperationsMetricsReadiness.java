package com.huawei.skillcenter.operations;

/** Admin-safe readiness projection for the shared operations metrics store. */
public record OperationsMetricsReadiness(
        String backend,
        String status,
        boolean shared,
        String reasonCode,
        String summary) {

    public OperationsMetricsReadiness {
        backend = normalize(backend, "unknown");
        status = normalize(status, "NOT_READY");
        reasonCode = normalize(reasonCode, "OPERATIONS_METRICS_UNKNOWN");
        summary = bounded(summary);
    }

    private static String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", "");
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String bounded(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", "");
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }
}
