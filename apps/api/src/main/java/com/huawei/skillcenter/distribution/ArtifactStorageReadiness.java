package com.huawei.skillcenter.distribution;

/** Safe operational projection for the configured immutable artifact backend. */
public record ArtifactStorageReadiness(
        String backend,
        String status,
        String reasonCode,
        String summary) {

    public ArtifactStorageReadiness {
        backend = normalize(backend, "unknown");
        status = normalize(status, "NOT_READY");
        reasonCode = normalize(reasonCode, "ARTIFACT_STORAGE_UNKNOWN");
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
