package com.huawei.skillcenter.release;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** Safe, status-only connectivity evidence for the configured release target. */
public record ReleaseTargetProbeResult(
        String targetId,
        String status,
        String reasonCode,
        Integer httpStatus,
        long latencyMs,
        Instant checkedAt) {
    private static final Set<String> STATUSES = Set.of(
            "REACHABLE", "NOT_CONFIGURED", "UNREACHABLE", "HTTP_ERROR", "FAILED", "TIMEOUT", "SKIPPED", "STALE");

    public ReleaseTargetProbeResult {
        targetId = required(targetId, "targetId");
        status = required(status, "status").toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("unsupported probe status");
        reasonCode = required(reasonCode, "reasonCode").toUpperCase(Locale.ROOT);
        if (!reasonCode.matches("[A-Z][A-Z0-9_.:-]{2,63}")) {
            throw new IllegalArgumentException("reasonCode must be a stable code");
        }
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be an HTTP status");
        }
        if (latencyMs < 0 || latencyMs > 120_000) throw new IllegalArgumentException("latencyMs is out of range");
        checkedAt = checkedAt == null ? Instant.EPOCH : checkedAt;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }
}
