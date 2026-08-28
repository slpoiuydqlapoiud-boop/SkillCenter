package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.Set;

public record TraceQuery(
        RuntimeOperationsWindow window,
        String skillId,
        String version,
        String traceId,
        String status,
        String dataSource,
        Instant now,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    private static final Set<String> STATUSES = Set.of("success", "failure", "timeout", "cancelled");
    private static final Set<String> SOURCES = Set.of("mock", "production", "all");

    public TraceQuery {
        window = window == null ? RuntimeOperationsWindow.TWENTY_FOUR_HOURS : window;
        skillId = normalize(skillId);
        version = normalize(version);
        traceId = normalize(traceId);
        status = normalize(status);
        dataSource = normalize(dataSource);
        runtimeId = normalizeEnvironment(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironment(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironment(llmProviderId, "llmProviderId");
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be success, failure, timeout or cancelled");
        }
        if (dataSource != null && !SOURCES.contains(dataSource)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
    }

    public TraceQuery(RuntimeOperationsWindow window, String skillId, String version,
                      String traceId, String status, String dataSource) {
        this(window, skillId, version, traceId, status, dataSource, null, null, null, null);
    }

    public TraceQuery(RuntimeOperationsWindow window, String skillId, String version,
                      String traceId, String status, String dataSource,
                      String runtimeId, String mcpServerId, String llmProviderId) {
        this(window, skillId, version, traceId, status, dataSource, null,
                runtimeId, mcpServerId, llmProviderId);
    }

    public Instant effectiveNow() {
        return now == null ? Instant.now() : now;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeEnvironment(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return null;
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
