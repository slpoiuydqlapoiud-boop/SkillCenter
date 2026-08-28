package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationExperimentDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void queuedExperimentMayNotCarryEvaluationReferences() {
        assertThatThrownBy(() -> experiment(OptimizationExperimentStatus.QUEUED, "run-1", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QUEUED");
    }

    @Test
    void runningExperimentRequiresAnEvaluationRun() {
        assertThatThrownBy(() -> experiment(OptimizationExperimentStatus.RUNNING, "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluationRunId");
    }

    @Test
    void completedExperimentRequiresAQualitySnapshot() {
        assertThatThrownBy(() -> experiment(OptimizationExperimentStatus.COMPLETED, "run-1", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qualitySnapshotId");
    }

    @Test
    void suiteIdentifiersMustBeProvidedTogether() {
        assertThatThrownBy(() -> new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0",
                "1.1.0", "mock", "", "", "", "smoke", "", OptimizationExperimentStatus.QUEUED,
                "", "", "", "", "admin", NOW, "admin", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suiteId");
    }

    @Test
    void failedExperimentRequiresStableFailureCode() {
        assertThatThrownBy(() -> experiment(OptimizationExperimentStatus.FAILED, "run-1", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
    }

    private OptimizationExperiment experiment(String status, String runId, String snapshotId,
                                              String benchmarkId, String failureCode) {
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "mock", "", "", "", "smoke", "smoke-v1", status, runId, snapshotId,
                benchmarkId, failureCode, "admin", NOW, "admin", NOW);
    }
}
