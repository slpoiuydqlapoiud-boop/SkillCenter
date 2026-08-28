package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import java.time.Instant;

public record EvaluationRun(
        String id,
        String skillId,
        String skillVersion,
        String suiteId,
        String suiteVersion,
        EvaluationRunStatus status,
        String providerId,
        String evaluationProviderId,
        String dataSource,
        Instant createdAt,
        Instant completedAt,
        int totalCases,
        int passedCases,
        int score,
        String errorCode,
        QualityGateStatus gateStatus,
        java.util.List<String> gateReasons,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String experimentId,
        ExecutionEnvironmentSnapshot runtimeEnvironment,
        ExecutionEnvironmentSnapshot mcpServerEnvironment,
        ExecutionEnvironmentSnapshot llmProviderEnvironment
) {
    public EvaluationRun(String id, String skillId, String skillVersion, String suiteId, String suiteVersion,
                         EvaluationRunStatus status, String providerId, String evaluationProviderId,
                         String dataSource, Instant createdAt, Instant completedAt, int totalCases,
                         int passedCases, int score, String errorCode, QualityGateStatus gateStatus,
                         java.util.List<String> gateReasons, String runtimeId, String mcpServerId,
                         String llmProviderId) {
        this(id, skillId, skillVersion, suiteId, suiteVersion, status, providerId, evaluationProviderId,
                dataSource, createdAt, completedAt, totalCases, passedCases, score, errorCode, gateStatus,
                gateReasons, runtimeId, mcpServerId, llmProviderId, "", null, null, null);
    }

    public EvaluationRun(String id, String skillId, String skillVersion, String suiteId, String suiteVersion,
                          EvaluationRunStatus status, String providerId, String evaluationProviderId,
                          String dataSource, Instant createdAt, Instant completedAt, int totalCases,
                          int passedCases, int score, String errorCode, QualityGateStatus gateStatus,
                          java.util.List<String> gateReasons) {
        this(id, skillId, skillVersion, suiteId, suiteVersion, status, providerId, evaluationProviderId,
                dataSource, createdAt, completedAt, totalCases, passedCases, score, errorCode, gateStatus,
                gateReasons, "", "", "", "", null, null, null);
    }

    public EvaluationRun(String id, String skillId, String skillVersion, String suiteId, String suiteVersion,
                          EvaluationRunStatus status, String providerId, String evaluationProviderId,
                          String dataSource, Instant createdAt, Instant completedAt, int totalCases,
                          int passedCases, int score, String errorCode, QualityGateStatus gateStatus,
                          java.util.List<String> gateReasons, String runtimeId, String mcpServerId,
                          String llmProviderId, String experimentId) {
        this(id, skillId, skillVersion, suiteId, suiteVersion, status, providerId, evaluationProviderId,
                dataSource, createdAt, completedAt, totalCases, passedCases, score, errorCode, gateStatus,
                gateReasons, runtimeId, mcpServerId, llmProviderId, experimentId, null, null, null);
    }

    public EvaluationRun {
        gateReasons = java.util.List.copyOf(gateReasons);
        runtimeId = environmentId(runtimeId);
        mcpServerId = environmentId(mcpServerId);
        llmProviderId = environmentId(llmProviderId);
        runtimeEnvironment = ExecutionEnvironmentSnapshot.normalize(ExecutionEnvironmentKind.AGENT_RUNTIME,
                runtimeId, runtimeEnvironment);
        mcpServerEnvironment = ExecutionEnvironmentSnapshot.normalize(ExecutionEnvironmentKind.MCP_SERVER,
                mcpServerId, mcpServerEnvironment);
        llmProviderEnvironment = ExecutionEnvironmentSnapshot.normalize(ExecutionEnvironmentKind.LLM_PROVIDER,
                llmProviderId, llmProviderEnvironment);
        experimentId = experimentId == null ? "" : experimentId.trim();
        if (!experimentId.isBlank() && !experimentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("experimentId must be a bounded identifier");
        }
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
