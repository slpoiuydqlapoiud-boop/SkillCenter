package com.huawei.skillcenter.operations;

import java.time.OffsetDateTime;

/** Redacted trace metadata only; never contains prompts, payloads, tools or credentials. */
public record TraceObservation(
        String traceId,
        String spanId,
        String skillId,
        String version,
        String operation,
        String status,
        long durationMs,
        String errorCode,
        String dataSource,
        OffsetDateTime occurredAt,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    public TraceObservation(String traceId, String spanId, String skillId, String version,
                            String operation, String status, long durationMs, String errorCode,
                            String dataSource, OffsetDateTime occurredAt) {
        this(traceId, spanId, skillId, version, operation, status, durationMs, errorCode,
                dataSource, occurredAt, "", "", "");
    }

    public TraceObservation {
        requireToken(traceId, "traceId");
        requireToken(spanId, "spanId");
        requireToken(skillId, "skillId");
        requireToken(version, "version");
        requireToken(operation, "operation");
        if (!java.util.Set.of("success", "failure", "timeout", "cancelled").contains(status)) {
            throw new IllegalArgumentException("trace status is invalid");
        }
        if (durationMs < 0 || durationMs > 86_400_000L) {
            throw new IllegalArgumentException("trace durationMs is invalid");
        }
        if (!java.util.Set.of("mock", "production").contains(dataSource)) {
            throw new IllegalArgumentException("trace dataSource is invalid");
        }
        if (occurredAt == null) throw new IllegalArgumentException("trace occurredAt is required");
        errorCode = errorCode == null ? "" : errorCode.trim();
        runtimeId = environmentId(runtimeId);
        mcpServerId = environmentId(mcpServerId);
        llmProviderId = environmentId(llmProviderId);
    }

    private static void requireToken(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
            throw new IllegalArgumentException("trace " + field + " is invalid");
        }
    }

    private static String environmentId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("trace execution environment identifier is invalid");
        }
        return normalized;
    }
}
