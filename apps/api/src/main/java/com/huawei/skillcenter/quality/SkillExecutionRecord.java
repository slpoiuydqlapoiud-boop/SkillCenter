package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import java.time.Instant;
import java.util.UUID;

public record SkillExecutionRecord(
        UUID executionId,
        String skillId,
        String skillVersion,
        RunnerExecutionStatus status,
        String providerId,
        String providerVersion,
        String dataSource,
        long durationMs,
        String outputHash,
        String errorCode,
        Instant executedAt,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        ExecutionEnvironmentSnapshot runtimeEnvironment,
        ExecutionEnvironmentSnapshot mcpServerEnvironment,
        ExecutionEnvironmentSnapshot llmProviderEnvironment
) {
    public SkillExecutionRecord(UUID executionId, String skillId, String skillVersion,
                                RunnerExecutionStatus status, String providerId, String providerVersion,
                                String dataSource, long durationMs, String outputHash, String errorCode,
                                Instant executedAt) {
        this(executionId, skillId, skillVersion, status, providerId, providerVersion, dataSource,
                durationMs, outputHash, errorCode, executedAt, "", "", "", null, null, null);
    }

    public SkillExecutionRecord(UUID executionId, String skillId, String skillVersion,
                                RunnerExecutionStatus status, String providerId, String providerVersion,
                                String dataSource, long durationMs, String outputHash, String errorCode,
                                Instant executedAt, String runtimeId, String mcpServerId, String llmProviderId) {
        this(executionId, skillId, skillVersion, status, providerId, providerVersion, dataSource,
                durationMs, outputHash, errorCode, executedAt, runtimeId, mcpServerId, llmProviderId,
                null, null, null);
    }

    public SkillExecutionRecord {
        if (executionId == null) throw new IllegalArgumentException("executionId is required");
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId is required");
        if (skillVersion == null || skillVersion.isBlank()) throw new IllegalArgumentException("skillVersion is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        providerId = providerId == null ? "" : providerId;
        providerVersion = providerVersion == null ? "" : providerVersion;
        dataSource = dataSource == null || dataSource.isBlank() ? "mock" : dataSource;
        outputHash = outputHash == null ? "" : outputHash;
        errorCode = errorCode == null ? "" : errorCode;
        executedAt = executedAt == null ? Instant.now() : executedAt;
        runtimeId = environmentId(runtimeId);
        mcpServerId = environmentId(mcpServerId);
        llmProviderId = environmentId(llmProviderId);
        runtimeEnvironment = ExecutionEnvironmentSnapshot.normalize(ExecutionEnvironmentKind.AGENT_RUNTIME,
                runtimeId, runtimeEnvironment);
        mcpServerEnvironment = ExecutionEnvironmentSnapshot.normalize(ExecutionEnvironmentKind.MCP_SERVER,
                mcpServerId, mcpServerEnvironment);
        llmProviderEnvironment = ExecutionEnvironmentSnapshot.normalize(ExecutionEnvironmentKind.LLM_PROVIDER,
                llmProviderId, llmProviderEnvironment);
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
