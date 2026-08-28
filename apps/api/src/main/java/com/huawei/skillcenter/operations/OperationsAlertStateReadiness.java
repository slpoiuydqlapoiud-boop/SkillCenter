package com.huawei.skillcenter.operations;

/** Admin-safe readiness projection for alert state persistence. */
public record OperationsAlertStateReadiness(
        String backend,
        String status,
        String reasonCode,
        String summary) {

    public OperationsAlertStateReadiness {
        backend = normalize(backend, "unknown");
        status = normalize(status, "NOT_READY");
        reasonCode = normalize(reasonCode, "OPERATIONS_ALERT_STATE_UNKNOWN");
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
