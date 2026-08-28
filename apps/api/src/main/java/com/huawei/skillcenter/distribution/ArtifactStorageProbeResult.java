package com.huawei.skillcenter.distribution;

import java.time.Instant;

/** Operator-safe artifact storage connectivity evidence. */
public record ArtifactStorageProbeResult(
        String backend,
        String status,
        String reasonCode,
        Integer httpStatus,
        long latencyMs,
        Instant checkedAt) {
    public ArtifactStorageProbeResult {
        backend = normalize(backend, "unknown");
        status = normalize(status, "FAILED");
        reasonCode = normalize(reasonCode, "ARTIFACT_STORAGE_PROBE_FAILED");
        latencyMs = Math.max(0, latencyMs);
        checkedAt = checkedAt == null ? Instant.EPOCH : checkedAt;
    }

    private static String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", "");
        return normalized.isBlank() ? fallback : normalized;
    }
}
