package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.time.OffsetDateTime;

public record RetentionPreview(
        String previewId,
        long policyVersion,
        OffsetDateTime expiresAt,
        Instant invocationCutoff,
        Instant installationCutoff,
        Instant auditCutoff,
        long invocationEligibleCount,
        long installationEligibleCount,
        long auditArchiveEligibleCount,
        long estimatedBytes,
        long runtimeSummaryEligibleCount,
        long qualityEvidenceEligibleCount,
        long benchmarkEligibleCount,
        long runnerExecutionEligibleCount,
        long compatibilityMatrixEligibleCount,
        long protectedEvaluationRunCount,
        long protectedQualitySnapshotCount,
        long protectedBenchmarkCount,
        long protectedCompatibilityMatrixCount,
        long protectedReferenceCount,
        String protectionFingerprint
) {
    public RetentionPreview(String previewId, long policyVersion, OffsetDateTime expiresAt,
                            Instant invocationCutoff, Instant installationCutoff, Instant auditCutoff,
                            long invocationEligibleCount, long installationEligibleCount,
                            long auditArchiveEligibleCount, long estimatedBytes,
                            long runtimeSummaryEligibleCount, long qualityEvidenceEligibleCount) {
        this(previewId, policyVersion, expiresAt, invocationCutoff, installationCutoff, auditCutoff,
                invocationEligibleCount, installationEligibleCount, auditArchiveEligibleCount, estimatedBytes,
                runtimeSummaryEligibleCount, qualityEvidenceEligibleCount, 0, 0, 0,
                0, 0, 0, 0, 0, "");
    }
}
