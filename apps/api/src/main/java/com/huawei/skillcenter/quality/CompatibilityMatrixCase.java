package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentStatus;

import java.time.Instant;
import java.util.List;

public record CompatibilityMatrixCase(
        String caseId,
        String matrixRunId,
        String evaluationRunId,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String runtimeVersion,
        String mcpServerVersion,
        String llmProviderVersion,
        ExecutionEnvironmentStatus runtimeStatus,
        ExecutionEnvironmentStatus mcpServerStatus,
        ExecutionEnvironmentStatus llmProviderStatus,
        CompatibilityMatrixCaseStatus status,
        int score,
        QualityGateStatus gateStatus,
        java.util.List<String> gateReasons,
        String errorCode,
        Instant startedAt,
        Instant completedAt,
        CompatibilityMatrixEnvironmentSnapshot runtimeEnvironment,
        CompatibilityMatrixEnvironmentSnapshot mcpServerEnvironment,
        CompatibilityMatrixEnvironmentSnapshot llmProviderEnvironment
) {
    public CompatibilityMatrixCase(String caseId, String matrixRunId, String evaluationRunId,
                                   String runtimeId, String mcpServerId, String llmProviderId,
                                   String runtimeVersion, String mcpServerVersion, String llmProviderVersion,
                                   ExecutionEnvironmentStatus runtimeStatus,
                                   ExecutionEnvironmentStatus mcpServerStatus,
                                   ExecutionEnvironmentStatus llmProviderStatus,
                                   CompatibilityMatrixCaseStatus status, int score,
                                   QualityGateStatus gateStatus, java.util.List<String> gateReasons,
                                   String errorCode, Instant startedAt, Instant completedAt) {
        this(caseId, matrixRunId, evaluationRunId, runtimeId, mcpServerId, llmProviderId,
                runtimeVersion, mcpServerVersion, llmProviderVersion, runtimeStatus,
                mcpServerStatus, llmProviderStatus, status, score, gateStatus, gateReasons,
                errorCode, startedAt, completedAt,
                CompatibilityMatrixEnvironmentSnapshot.of(runtimeId, runtimeVersion, runtimeStatus, List.of(), ""),
                CompatibilityMatrixEnvironmentSnapshot.of(mcpServerId, mcpServerVersion, mcpServerStatus, List.of(), ""),
                CompatibilityMatrixEnvironmentSnapshot.of(llmProviderId, llmProviderVersion, llmProviderStatus, List.of(), ""));
    }

    public CompatibilityMatrixCase {
        caseId = required(caseId, "caseId");
        matrixRunId = required(matrixRunId, "matrixRunId");
        if (evaluationRunId == null) evaluationRunId = "";
        runtimeId = bounded(runtimeId, "runtimeId");
        mcpServerId = bounded(mcpServerId, "mcpServerId");
        llmProviderId = bounded(llmProviderId, "llmProviderId");
        runtimeVersion = optionalText(runtimeVersion);
        mcpServerVersion = optionalText(mcpServerVersion);
        llmProviderVersion = optionalText(llmProviderVersion);
        if (status == null) throw new IllegalArgumentException("status is required");
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        if (gateStatus == null) throw new IllegalArgumentException("gateStatus is required");
        gateReasons = java.util.List.copyOf(gateReasons == null ? java.util.List.of() : gateReasons);
        if (status.terminal() && completedAt == null) throw new IllegalArgumentException("completedAt is required for terminal case");
        if (startedAt != null && completedAt != null && completedAt.isBefore(startedAt)) throw new IllegalArgumentException("completedAt must not be before startedAt");
        runtimeEnvironment = normalizeSnapshot(runtimeEnvironment, runtimeId, runtimeVersion, runtimeStatus, "runtime");
        mcpServerEnvironment = normalizeSnapshot(mcpServerEnvironment, mcpServerId, mcpServerVersion, mcpServerStatus, "mcpServer");
        llmProviderEnvironment = normalizeSnapshot(llmProviderEnvironment, llmProviderId, llmProviderVersion, llmProviderStatus, "llmProvider");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String bounded(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static CompatibilityMatrixEnvironmentSnapshot normalizeSnapshot(
            CompatibilityMatrixEnvironmentSnapshot snapshot, String environmentId, String version,
            ExecutionEnvironmentStatus status, String field) {
        CompatibilityMatrixEnvironmentSnapshot normalized = snapshot == null
                ? CompatibilityMatrixEnvironmentSnapshot.of(environmentId, version, status, java.util.List.of(), "")
                : snapshot;
        if (!normalized.environmentId().equals(environmentId)
                || !normalized.version().equals(version)
                || normalized.status() != status) {
            throw new IllegalArgumentException(field + " environment snapshot does not match case context");
        }
        return normalized;
    }
}
