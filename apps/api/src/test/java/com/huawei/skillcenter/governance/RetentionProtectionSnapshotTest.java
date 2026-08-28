package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionProtectionSnapshotTest {
    @Test
    void producesStableFingerprintAndDeduplicatedProtectionSets() {
        RetentionEvidenceReference workItemRun = new RetentionEvidenceReference(
                "WORK_ITEM", "work-1", "EVALUATION_RUN", "run-1");
        RetentionEvidenceReference experimentSnapshot = new RetentionEvidenceReference(
                "EXPERIMENT", "experiment-1", "QUALITY_SNAPSHOT", "snapshot-1");
        RetentionEvidenceReference releaseMatrix = new RetentionEvidenceReference(
                "RELEASE", "release-1", "COMPATIBILITY_MATRIX", "matrix-1");
        RetentionEvidenceReference benchmark = new RetentionEvidenceReference(
                "EXPERIMENT", "experiment-1", "BENCHMARK", "benchmark-1");

        RetentionProtectionSnapshot first = RetentionProtectionSnapshot.from(
                List.of(workItemRun, experimentSnapshot, releaseMatrix, benchmark, workItemRun));
        RetentionProtectionSnapshot reordered = RetentionProtectionSnapshot.from(
                List.of(benchmark, releaseMatrix, experimentSnapshot, workItemRun));

        assertThat(first.fingerprint()).isEqualTo(reordered.fingerprint());
        assertThat(first.referenceCount()).isEqualTo(4);
        assertThat(first.evaluationRunIds()).containsExactly("run-1");
        assertThat(first.qualitySnapshotIds()).containsExactly("snapshot-1");
        assertThat(first.compatibilityMatrixIds()).containsExactly("matrix-1");
        assertThat(first.benchmarkIds()).containsExactly("benchmark-1");
        assertThat(first.references()).hasSize(4);
    }

    @Test
    void exposesImmutableSetsAndEmptySnapshot() {
        RetentionProtectionSnapshot snapshot = RetentionProtectionSnapshot.from(List.of(
                new RetentionEvidenceReference("WORK_ITEM", "work-1", "BENCHMARK", "benchmark-1")));

        assertThatThrownBy(() -> snapshot.benchmarkIds().add("benchmark-2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(RetentionProtectionSnapshot.empty().referenceCount()).isZero();
        assertThat(RetentionProtectionSnapshot.empty().fingerprint()).isNotBlank();
    }

    @Test
    void rejectsInvalidEvidenceReferences() {
        assertThatThrownBy(() -> new RetentionEvidenceReference("", "source-1", "BENCHMARK", "benchmark-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionEvidenceReference("WORK_ITEM", "source-1", "", "benchmark-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionEvidenceReference("WORK_ITEM", "source-1", "BENCHMARK", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
