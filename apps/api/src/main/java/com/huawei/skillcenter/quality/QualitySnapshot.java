package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import java.time.Instant;

public record QualitySnapshot(
        String snapshotId,
        String skillId,
        String skillVersion,
        String suiteId,
        String suiteVersion,
        String runnerId,
        String evaluationProviderId,
        String dataSource,
        Instant measuredAt,
        int score,
        int totalCases,
        int passedCases,
        boolean comparable,
        String ruleVersion,
        int staticScore,
        double passRate,
        QualityGateStatus gateStatus,
        java.util.List<String> gateReasons,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        ExecutionEnvironmentSnapshot runtimeEnvironment,
        ExecutionEnvironmentSnapshot mcpServerEnvironment,
        ExecutionEnvironmentSnapshot llmProviderEnvironment
) {
    public QualitySnapshot(String snapshotId, String skillId, String skillVersion, String suiteId,
                           String suiteVersion, String runnerId, String evaluationProviderId, String dataSource,
                           Instant measuredAt, int score, int totalCases, int passedCases, boolean comparable,
                           String ruleVersion, int staticScore, double passRate, QualityGateStatus gateStatus,
                           java.util.List<String> gateReasons) {
        this(snapshotId, skillId, skillVersion, suiteId, suiteVersion, runnerId, evaluationProviderId, dataSource,
                measuredAt, score, totalCases, passedCases, comparable, ruleVersion, staticScore, passRate,
                gateStatus, gateReasons, "", "", "", null, null, null);
    }

    public QualitySnapshot(String snapshotId, String skillId, String skillVersion, String suiteId,
                           String suiteVersion, String runnerId, String evaluationProviderId, String dataSource,
                           Instant measuredAt, int score, int totalCases, int passedCases, boolean comparable,
                           String ruleVersion, int staticScore, double passRate, QualityGateStatus gateStatus,
                           java.util.List<String> gateReasons, String runtimeId, String mcpServerId,
                           String llmProviderId) {
        this(snapshotId, skillId, skillVersion, suiteId, suiteVersion, runnerId, evaluationProviderId, dataSource,
                measuredAt, score, totalCases, passedCases, comparable, ruleVersion, staticScore, passRate,
                gateStatus, gateReasons, runtimeId, mcpServerId, llmProviderId, null, null, null);
    }

    public QualitySnapshot {
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
