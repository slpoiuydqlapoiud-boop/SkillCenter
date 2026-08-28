package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QualityEvidenceRetentionProtectionTest {
    @Test
    void keepsProtectedRunAndSnapshotWhileDeletingOtherExpiredEvidence() {
        Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");
        Instant old = cutoff.minusSeconds(1);
        EvaluationRun protectedRun = run("run-protected", old);
        EvaluationRun expiredRun = run("run-expired", old);
        QualitySnapshot protectedSnapshot = new QualitySnapshot("run-protected", "skill-a", "1.0.0", "suite",
                "v1", "runner", "provider", "mock", old, 80, 1, 1, true, "rule", 80, 1.0,
                QualityGateStatus.PASSED, List.of());
        QualityEvidenceStore store = new QualityEvidenceStore();
        store.save(new QualityEvidenceState(List.of(), null, List.of(protectedRun, expiredRun),
                List.of(protectedSnapshot)));
        QualityEvidenceRetentionProtection protection = new QualityEvidenceRetentionProtection(
                Set.of("run-protected"), Set.of("run-protected"), Set.of());

        assertThat(store.countBefore(cutoff, protection)).isEqualTo(1);
        assertThat(store.deleteBefore(cutoff, protection)).isEqualTo(1);
        assertThat(store.load().runs()).extracting(EvaluationRun::id).containsExactly("run-protected");
        assertThat(store.load().snapshots()).extracting(QualitySnapshot::snapshotId).containsExactly("run-protected");
    }

    @Test
    void protectsMatrixAndItsReferencedEvaluationRunAsOneEvidenceChain() {
        Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");
        Instant old = cutoff.minusSeconds(1);
        String matrixId = UUID.randomUUID().toString();
        EvaluationRun evaluation = new EvaluationRun("matrix-evaluation", "skill-a", "1.0.0", "suite", "v1",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", old, old, 1, 1, 80, "",
                QualityGateStatus.PASSED, List.of(), "runtime", "", "");
        CompatibilityMatrixRun matrix = new CompatibilityMatrixRun(matrixId, "skill-a", "1.0.0", "suite", "v1",
                CompatibilityMatrixPolicy.ALL_MUST_PASS, 1, false, CompatibilityMatrixStatus.COMPLETED, "mock",
                "success", 1000, 1, 1, 1, 100, 1, QualityGateStatus.PASSED, List.of(), "admin", old, old);
        CompatibilityMatrixCase matrixCase = new CompatibilityMatrixCase("case-1", matrixId, "matrix-evaluation",
                "runtime", "", "", "v1", "", "", com.huawei.skillcenter.execution.ExecutionEnvironmentStatus.ACTIVE,
                null, null, CompatibilityMatrixCaseStatus.COMPLETED, 100, QualityGateStatus.PASSED, List.of(), "", old, old);
        QualityEvidenceStore store = new QualityEvidenceStore();
        store.save(new QualityEvidenceState(List.of(), null, List.of(evaluation), List.of(), List.of(),
                List.of(matrix), List.of(matrixCase)));
        QualityEvidenceRetentionProtection protection = new QualityEvidenceRetentionProtection(
                Set.of(), Set.of(), Set.of(matrixId));

        assertThat(store.countBefore(cutoff, protection)).isZero();
        assertThat(store.deleteBefore(cutoff, protection)).isZero();
        assertThat(store.load().matrixRuns()).extracting(CompatibilityMatrixRun::matrixRunId).containsExactly(matrixId);
        assertThat(store.load().matrixCases()).extracting(CompatibilityMatrixCase::caseId).containsExactly("case-1");
        assertThat(store.load().runs()).extracting(EvaluationRun::id).containsExactly("matrix-evaluation");
    }

    private static EvaluationRun run(String id, Instant createdAt) {
        return new EvaluationRun(id, "skill-a", "1.0.0", "suite", "v1", EvaluationRunStatus.COMPLETED,
                "runner", "provider", "mock", createdAt, createdAt, 1, 1, 80, "", QualityGateStatus.PASSED,
                List.of());
    }
}
