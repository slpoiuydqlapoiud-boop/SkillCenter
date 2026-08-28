package com.huawei.skillcenter.quality;

import java.time.Instant;

public record RunnerExecutionSummary(
        String runId,
        String skillId,
        String skillVersion,
        RunnerExecutionStatus status,
        long durationMs,
        String errorCode,
        String dataSource,
        Instant occurredAt,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    public RunnerExecutionSummary(String runId, String skillId, String skillVersion,
                                  RunnerExecutionStatus status, long durationMs,
                                  String errorCode, String dataSource) {
        this(runId, skillId, skillVersion, status, durationMs, errorCode, dataSource, Instant.now());
    }

    public RunnerExecutionSummary(String runId, String skillId, String skillVersion,
                                  RunnerExecutionStatus status, long durationMs, String errorCode,
                                  String dataSource, Instant occurredAt) {
        this(runId, skillId, skillVersion, status, durationMs, errorCode, dataSource, occurredAt, "", "", "");
    }

    public RunnerExecutionSummary {
        runtimeId = environmentId(runtimeId);
        mcpServerId = environmentId(mcpServerId);
        llmProviderId = environmentId(llmProviderId);
    }

    private static String environmentId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("execution environment identifier is invalid");
        }
        return normalized;
    }
}
