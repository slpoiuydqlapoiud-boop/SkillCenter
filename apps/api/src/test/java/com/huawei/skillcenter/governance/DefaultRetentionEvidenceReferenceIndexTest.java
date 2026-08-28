package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.quality.OptimizationExperiment;
import com.huawei.skillcenter.quality.OptimizationExperimentStore;
import com.huawei.skillcenter.quality.OptimizationWorkItem;
import com.huawei.skillcenter.quality.OptimizationWorkItemRepository;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import com.huawei.skillcenter.release.ReleaseRecord;
import com.huawei.skillcenter.release.ReleaseRecordRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultRetentionEvidenceReferenceIndexTest {
    @Test
    void indexesWorkItemsExperimentsAndReleaseGateEvidence() {
        OptimizationWorkItemRepository workItems = mock(OptimizationWorkItemRepository.class);
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        ReleaseRecordRepository releases = mock(ReleaseRecordRepository.class);
        when(workItems.findAll(null, null, null, null)).thenReturn(List.of(workItem()));
        when(experiments.findAll(null, null, null)).thenReturn(List.of(experiment()));
        when(releases.findAll(null, null, null, null)).thenReturn(List.of(release()));

        RetentionProtectionSnapshot snapshot = new DefaultRetentionEvidenceReferenceIndex(
                workItems, experiments, releases).snapshot();

        assertThat(snapshot.evaluationRunIds()).containsExactlyInAnyOrder("run-work", "run-experiment");
        assertThat(snapshot.qualitySnapshotIds()).containsExactlyInAnyOrder("snapshot-experiment", "snapshot-release");
        assertThat(snapshot.benchmarkIds()).containsExactly("benchmark-experiment");
        assertThat(snapshot.compatibilityMatrixIds()).containsExactly("matrix-release");
        assertThat(snapshot.referenceCount()).isEqualTo(6);
    }

    @Test
    void failsClosedWhenAnyLifecycleSourceCannotBeRead() {
        OptimizationWorkItemRepository workItems = mock(OptimizationWorkItemRepository.class);
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        ReleaseRecordRepository releases = mock(ReleaseRecordRepository.class);
        when(workItems.findAll(null, null, null, null)).thenThrow(new IllegalStateException("storage down"));

        assertThatThrownBy(() -> new DefaultRetentionEvidenceReferenceIndex(workItems, experiments, releases).snapshot())
                .isInstanceOf(RetentionException.class)
                .extracting(exception -> ((RetentionException) exception).code())
                .isEqualTo("RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE");
    }

    private static OptimizationWorkItem workItem() {
        return new OptimizationWorkItem("work-1", "skill-a", "1.0.0", "suggestion-1", "Improve", "QUALITY",
                "MEDIUM", List.of(), "Hypothesis", "owner-1", "COMPLETED", "1.1.0",
                OptimizationWorkItem.EVALUATION_RUN, "run-work", "done", "mock", "runtime", "mcp", "llm",
                "suite", "v1", "admin", Instant.parse("2026-08-20T00:00:00Z"), "admin",
                Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static OptimizationExperiment experiment() {
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0", "mock",
                "runtime", "mcp", "llm", "suite", "v1", "COMPLETED", "run-experiment",
                "snapshot-experiment", "benchmark-experiment", "", "admin",
                Instant.parse("2026-08-20T00:00:00Z"), "admin", Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static ReleaseRecord release() {
        ReleaseGateSnapshot gate = new ReleaseGateSnapshot(Instant.parse("2026-08-20T00:00:00Z"), "PASSED",
                List.of(), "snapshot-release", "experiment-1", "PASSED", "matrix-release", "mock", "suite", "v1",
                "runtime", "mcp", "llm");
        return new ReleaseRecord("release-1", "skill-a", "1.1.0", "a".repeat(64),
                com.huawei.skillcenter.release.ReleaseEnvironment.PRODUCTION, gate, "", "", "", "", "",
                "idempotency-1", com.huawei.skillcenter.release.ReleaseStatus.PROMOTED, "admin",
                Instant.parse("2026-08-20T00:00:00Z"), "admin", Instant.parse("2026-08-20T00:00:00Z"),
                "", "", Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"),
                "admin", Instant.parse("2026-08-20T00:00:00Z"));
    }
}
