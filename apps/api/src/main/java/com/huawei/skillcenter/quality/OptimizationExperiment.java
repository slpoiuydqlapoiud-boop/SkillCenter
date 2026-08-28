package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.Locale;

public record OptimizationExperiment(
        String experimentId,
        String workItemId,
        String skillId,
        String sourceVersion,
        String candidateVersion,
        String dataSource,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String suiteId,
        String suiteVersion,
        String status,
        String evaluationRunId,
        String qualitySnapshotId,
        String benchmarkId,
        String failureCode,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        OptimizationExperimentDecision decision
) {
    public OptimizationExperiment(String experimentId,
                                   String workItemId,
                                   String skillId,
                                   String sourceVersion,
                                   String candidateVersion,
                                   String dataSource,
                                   String runtimeId,
                                   String mcpServerId,
                                   String llmProviderId,
                                   String suiteId,
                                   String suiteVersion,
                                   String status,
                                   String evaluationRunId,
                                   String qualitySnapshotId,
                                   String benchmarkId,
                                   String failureCode,
                                   String createdBy,
                                   Instant createdAt,
                                   String updatedBy,
                                   Instant updatedAt) {
        this(experimentId, workItemId, skillId, sourceVersion, candidateVersion, dataSource, runtimeId, mcpServerId,
                llmProviderId, suiteId, suiteVersion, status, evaluationRunId, qualitySnapshotId, benchmarkId,
                failureCode, createdBy, createdAt, updatedBy, updatedAt, null);
    }

    public OptimizationExperiment {
        requireIdentifier(experimentId, "experimentId");
        requireIdentifier(workItemId, "workItemId");
        requireIdentifier(skillId, "skillId");
        requireText(sourceVersion, "sourceVersion", 128);
        requireText(candidateVersion, "candidateVersion", 128);
        dataSource = normalizeDataSource(dataSource);
        runtimeId = normalizeEnvironment(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironment(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironment(llmProviderId, "llmProviderId");
        suiteId = normalizeOptionalIdentifier(suiteId, "suiteId");
        suiteVersion = normalizeOptionalIdentifier(suiteVersion, "suiteVersion");
        if (suiteId.isBlank() != suiteVersion.isBlank()) {
            throw new IllegalArgumentException("suiteId and suiteVersion must be provided together");
        }
        status = OptimizationExperimentStatus.normalize(status);
        evaluationRunId = normalizeOptionalIdentifier(evaluationRunId, "evaluationRunId");
        qualitySnapshotId = normalizeOptionalIdentifier(qualitySnapshotId, "qualitySnapshotId");
        benchmarkId = normalizeOptionalIdentifier(benchmarkId, "benchmarkId");
        failureCode = normalizeFailureCode(failureCode);
        requireIdentifier(createdBy, "createdBy");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        requireIdentifier(updatedBy, "updatedBy");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        validateState(status, evaluationRunId, qualitySnapshotId, benchmarkId, failureCode, decision);
        if (decision != null && (!experimentId.equals(decision.experimentId())
                || !skillId.equals(decision.skillId())
                || !sourceVersion.equals(decision.sourceVersion())
                || !candidateVersion.equals(decision.candidateVersion())
                || !dataSource.equals(decision.dataSource())
                || !runtimeId.equals(decision.runtimeId())
                || !mcpServerId.equals(decision.mcpServerId())
                || !llmProviderId.equals(decision.llmProviderId())
                || !suiteId.equals(decision.suiteId())
                || !suiteVersion.equals(decision.suiteVersion())
                || !qualitySnapshotId.equals(decision.qualitySnapshotId())
                || !benchmarkId.equals(decision.benchmarkId()))) {
            throw new IllegalArgumentException("decision context does not match experiment");
        }
    }

    private static void validateState(String status, String evaluationRunId, String qualitySnapshotId,
                                      String benchmarkId, String failureCode,
                                      OptimizationExperimentDecision decision) {
        if (OptimizationExperimentStatus.QUEUED.equals(status) && !evaluationRunId.isBlank()) {
            throw new IllegalArgumentException("QUEUED experiment must not have evaluationRunId");
        }
        if (OptimizationExperimentStatus.RUNNING.equals(status) && evaluationRunId.isBlank()) {
            throw new IllegalArgumentException("RUNNING experiment requires evaluationRunId");
        }
        if (OptimizationExperimentStatus.COMPLETED.equals(status)) {
            if (evaluationRunId.isBlank()) throw new IllegalArgumentException("COMPLETED experiment requires evaluationRunId");
            if (qualitySnapshotId.isBlank()) throw new IllegalArgumentException("COMPLETED experiment requires qualitySnapshotId");
        }
        if (OptimizationExperimentStatus.FAILED.equals(status) && failureCode.isBlank()) {
            throw new IllegalArgumentException("FAILED experiment requires failureCode");
        }
        if (OptimizationExperimentStatus.CANCELLED.equals(status)
                && !"CANCELLED_BY_REQUEST".equals(failureCode)) {
            throw new IllegalArgumentException("CANCELLED experiment requires CANCELLED_BY_REQUEST");
        }
        if (!OptimizationExperimentStatus.COMPLETED.equals(status) && !benchmarkId.isBlank()) {
            throw new IllegalArgumentException("benchmarkId requires COMPLETED experiment");
        }
        if (!OptimizationExperimentStatus.FAILED.equals(status)
                && !OptimizationExperimentStatus.CANCELLED.equals(status)
                && !failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode is only valid for failed or cancelled experiments");
        }
        if (decision != null && !OptimizationExperimentStatus.COMPLETED.equals(status)) {
            throw new IllegalArgumentException("decision requires COMPLETED experiment");
        }
    }

    private static String normalizeDataSource(String value) {
        String normalized = value == null || value.isBlank() ? "all" : value.trim().toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("mock", "production", "all").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private static String normalizeEnvironment(String value, String field) {
        String normalized = normalizeOptionalIdentifier(value, field);
        if (!normalized.isBlank() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeOptionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 128) throw new IllegalArgumentException(field + " exceeds maximum length");
        if (!normalized.isBlank()) requireIdentifier(normalized, field);
        return normalized;
    }

    private static String normalizeFailureCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 64 || (!normalized.isBlank() && !normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}"))) {
            throw new IllegalArgumentException("failureCode must be a stable bounded code");
        }
        return normalized;
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (value.trim().length() > max) throw new IllegalArgumentException(field + " exceeds maximum length");
    }

    private static void requireIdentifier(String value, String field) {
        requireText(value, field, 128);
        if (!value.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
