package com.huawei.skillcenter.operations;

/** Safe readiness projection for the optimization experiment evidence backend. */
public record OptimizationExperimentBackendReadiness(
        String backend,
        String status,
        String reasonCode,
        String summary) {
    public OptimizationExperimentBackendReadiness {
        backend = required(backend, "backend");
        status = required(status, "status");
        reasonCode = normalize(reasonCode);
        summary = normalize(summary);
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", "");
    }
}
