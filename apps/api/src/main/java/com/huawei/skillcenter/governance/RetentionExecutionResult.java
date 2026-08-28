package com.huawei.skillcenter.governance;

public record RetentionExecutionResult(String executionId, String previewId, long policyVersion,
                                       long invocationDeleted, long installationDeleted,
                                       long auditArchiveEligibleCount, boolean idempotent,
                                       long runtimeSummaryDeleted,
                                       long qualityEvidenceDeleted,
                                       long benchmarkDeleted,
                                       long runnerExecutionDeleted,
                                       long compatibilityMatrixDeleted,
                                       long protectedEvaluationRunCount,
                                       long protectedQualitySnapshotCount,
                                       long protectedBenchmarkCount,
                                       long protectedCompatibilityMatrixCount,
                                       long protectedReferenceCount,
                                       String protectionFingerprint) {
    public RetentionExecutionResult(String executionId, String previewId, long policyVersion,
                                    long invocationDeleted, long installationDeleted,
                                    long auditArchiveEligibleCount, boolean idempotent,
                                    long runtimeSummaryDeleted, long qualityEvidenceDeleted) {
        this(executionId, previewId, policyVersion, invocationDeleted, installationDeleted,
                auditArchiveEligibleCount, idempotent, runtimeSummaryDeleted, qualityEvidenceDeleted,
                0, 0, 0, 0, 0, 0, 0, 0, "");
    }

    public RetentionExecutionResult(String executionId, String previewId, long policyVersion,
                                    long invocationDeleted, long installationDeleted,
                                    long auditArchiveEligibleCount, boolean idempotent,
                                    long runtimeSummaryDeleted, long qualityEvidenceDeleted,
                                    long benchmarkDeleted) {
        this(executionId, previewId, policyVersion, invocationDeleted, installationDeleted,
                auditArchiveEligibleCount, idempotent, runtimeSummaryDeleted, qualityEvidenceDeleted,
                benchmarkDeleted, 0, 0, 0, 0, 0, 0, 0, "");
    }
}
