package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.CompatibilityMatrixPolicy;
import com.huawei.skillcenter.quality.CompatibilityMatrixRun;
import com.huawei.skillcenter.quality.CompatibilityMatrixStatus;
import com.huawei.skillcenter.quality.MockEvaluationProvider;
import com.huawei.skillcenter.quality.MockRunner;
import com.huawei.skillcenter.quality.QualityEvidenceStore;
import com.huawei.skillcenter.quality.QualityGateStatus;
import com.huawei.skillcenter.quality.QualityEvaluationService;
import com.huawei.skillcenter.quality.OptimizationExperiment;
import com.huawei.skillcenter.quality.OptimizationExperimentDecision;
import com.huawei.skillcenter.quality.OptimizationExperimentStatus;
import com.huawei.skillcenter.quality.OptimizationExperimentStore;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityEvaluationReleaseGateTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitPassedCompatibilityMatrixControlsPublishing() {
        QualityEvidenceStore evidence = new QualityEvidenceStore(tempDir.resolve("quality-pass.json"),
                new ObjectMapper().findAndRegisterModules());
        evidence.save(new com.huawei.skillcenter.quality.QualityEvidenceState(List.of(), null, List.of(), List.of(), List.of(),
                List.of(matrix(CompatibilityMatrixStatus.COMPLETED, QualityGateStatus.PASSED, List.of())), List.of()));
        QualityEvaluationReleaseGate gate = new QualityEvaluationReleaseGate(
                new QualityEvaluationService(new MockRunner(), new MockEvaluationProvider(), Clock.systemUTC(), evidence), evidence);

        assertThatCode(() -> gate.ensurePublishable("skill-a", "1.0.0")).doesNotThrowAnyException();
    }

    @Test
    void incompleteOrBlockedCompatibilityMatrixBlocksPublishingWithStableReasons() {
        QualityEvidenceStore evidence = new QualityEvidenceStore(tempDir.resolve("quality-blocked.json"),
                new ObjectMapper().findAndRegisterModules());
        evidence.save(new com.huawei.skillcenter.quality.QualityEvidenceState(List.of(), null, List.of(), List.of(), List.of(),
                List.of(matrix(CompatibilityMatrixStatus.RUNNING, QualityGateStatus.BLOCKED,
                        List.of("EVALUATION_NOT_COMPLETED"))), List.of()));
        QualityEvaluationReleaseGate gate = new QualityEvaluationReleaseGate(
                new QualityEvaluationService(new MockRunner(), new MockEvaluationProvider(), Clock.systemUTC(), evidence), evidence);

        assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.0.0"))
                .isInstanceOf(QualityGateBlockedException.class)
                .extracting(Throwable::getMessage).asString().contains("COMPATIBILITY_MATRIX_INCOMPLETE");
    }

    @Test
    void legacyVersionWithoutOptimizationExperimentRemainsPublishable() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        when(experiments.findAll("skill-a", null, null)).thenReturn(List.of());
        QualityEvaluationReleaseGate gate = gate(experiments);

        assertThatCode(() -> gate.ensurePublishable("skill-a", "1.0.0")).doesNotThrowAnyException();
    }

    @Test
    void activeOptimizationExperimentBlocksPublishingWithStableReason() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        when(experiments.findAll("skill-a", null, null))
                .thenReturn(List.of(experiment("running-1", OptimizationExperimentStatus.RUNNING, null)));
        QualityEvaluationReleaseGate gate = gate(experiments);

        assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.1.0"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("OPTIMIZATION_EXPERIMENT_INCOMPLETE");
    }

    @Test
    void failedAndCancelledOptimizationExperimentsBlockWithDistinctReasons() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        QualityEvaluationReleaseGate gate = gate(experiments);

        when(experiments.findAll("skill-a", null, null))
                .thenReturn(List.of(experiment("failed-1", OptimizationExperimentStatus.FAILED, null)));
        assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.1.0"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("OPTIMIZATION_EXPERIMENT_FAILED");

        when(experiments.findAll("skill-a", null, null))
                .thenReturn(List.of(experiment("cancelled-1", OptimizationExperimentStatus.CANCELLED, null)));
        assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.1.0"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("OPTIMIZATION_EXPERIMENT_CANCELLED");
    }

    @Test
    void completedExperimentWithoutDecisionBlocksPublishing() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        when(experiments.findAll("skill-a", null, null))
                .thenReturn(List.of(experiment("completed-1", OptimizationExperimentStatus.COMPLETED, null)));
        QualityEvaluationReleaseGate gate = gate(experiments);

        assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.1.0"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("OPTIMIZATION_DECISION_REQUIRED");
    }

    @Test
    void nonPromotingDecisionBlocksPublishing() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        OptimizationExperimentDecision decision = decision("ITERATE");
        when(experiments.findAll("skill-a", null, null))
                .thenReturn(List.of(experiment("completed-1", OptimizationExperimentStatus.COMPLETED, decision)));
        QualityEvaluationReleaseGate gate = gate(experiments);

        assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.1.0"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("OPTIMIZATION_DECISION_BLOCKED");
    }

    @Test
    void promotedDecisionPassesOptimizationGateAndKeepsExistingChecks() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        when(experiments.findAll("skill-a", null, null))
                .thenReturn(List.of(experiment("completed-1", OptimizationExperimentStatus.COMPLETED,
                        decision(OptimizationExperimentDecision.PROMOTE_CANDIDATE))));
        QualityEvaluationReleaseGate gate = gate(experiments);

        assertThatCode(() -> gate.ensurePublishable("skill-a", "1.1.0")).doesNotThrowAnyException();
    }

    @Test
    void evaluateReturnsEvidenceIdsForAReleaseSnapshot() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        when(experiments.findAll("skill-a", null, null))
                .thenReturn(List.of(experiment("completed-1", OptimizationExperimentStatus.COMPLETED,
                        decision(OptimizationExperimentDecision.PROMOTE_CANDIDATE))));
        QualityEvaluationReleaseGate gate = gate(experiments);

        ReleaseGateSnapshot snapshot = gate.evaluate("skill-a", "1.1.0");

        assertThat(snapshot.outcome()).isEqualTo("PASSED");
        assertThat(snapshot.optimizationExperimentId()).isEqualTo("completed-1");
        assertThat(snapshot.optimizationDecision()).isEqualTo(OptimizationExperimentDecision.PROMOTE_CANDIDATE);
    }

    @Test
    void latestTerminalExperimentDeterminesThePublishDecision() {
        OptimizationExperimentStore experiments = mock(OptimizationExperimentStore.class);
        OptimizationExperiment older = experiment("completed-old", OptimizationExperimentStatus.COMPLETED,
                decision(OptimizationExperimentDecision.PROMOTE_CANDIDATE));
        OptimizationExperiment latest = new OptimizationExperiment("completed-new", "work-1", "skill-a", "1.0.0",
                "1.1.0", "mock", "", "", "", "", "", OptimizationExperimentStatus.COMPLETED,
                "run-1", "snapshot-1", "benchmark-1", "", "admin", NOW, "admin", NOW.plusSeconds(1),
                decision("completed-new", "REJECT_CANDIDATE"));
        when(experiments.findAll("skill-a", null, null)).thenReturn(List.of(older, latest));
        QualityEvaluationReleaseGate gate = gate(experiments);

        assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.1.0"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("OPTIMIZATION_DECISION_BLOCKED");
    }

    private QualityEvaluationReleaseGate gate(OptimizationExperimentStore experiments) {
        return new QualityEvaluationReleaseGate(
                new QualityEvaluationService(new MockRunner(), new MockEvaluationProvider(), Clock.systemUTC(), null),
                null, experiments);
    }

    private OptimizationExperiment experiment(String id, String status, OptimizationExperimentDecision decision) {
        if (decision != null && !id.equals(decision.experimentId())) {
            decision = decision(id, decision.decision());
        }
        String failureCode = OptimizationExperimentStatus.FAILED.equals(status) ? "EVALUATION_FAILED"
                : OptimizationExperimentStatus.CANCELLED.equals(status) ? "CANCELLED_BY_REQUEST" : "";
        String evaluationRunId = OptimizationExperimentStatus.QUEUED.equals(status) ? "" : "run-1";
        String snapshotId = OptimizationExperimentStatus.COMPLETED.equals(status) ? "snapshot-1" : "";
        String benchmarkId = OptimizationExperimentStatus.COMPLETED.equals(status) ? "benchmark-1" : "";
        return new OptimizationExperiment(id, "work-1", "skill-a", "1.0.0", "1.1.0", "mock", "", "", "", "", "",
                status, evaluationRunId, snapshotId, benchmarkId, failureCode, "admin", NOW, "admin", NOW, decision);
    }

    private OptimizationExperimentDecision decision(String value) {
        return decision("completed-1", value);
    }

    private OptimizationExperimentDecision decision(String experimentId, String value) {
        return new OptimizationExperimentDecision("decision-" + experimentId, experimentId, "skill-a", "1.0.0", "1.1.0", "mock",
                "", "", "", "", "", "snapshot-1", "benchmark-1", QualityGateStatus.PASSED, "IMPROVED", value,
                "BENCHMARK_EFFECT", "benchmark result", "manual release review", "admin", NOW);
    }

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    private CompatibilityMatrixRun matrix(CompatibilityMatrixStatus status, QualityGateStatus gateStatus,
                                          List<String> reasons) {
        return new CompatibilityMatrixRun(UUID.randomUUID().toString(), "skill-a", "1.0.0", "smoke", "smoke-v1",
                CompatibilityMatrixPolicy.ALL_MUST_PASS, 1, true, status, "mock", "success", 1_000,
                0, 0, 0, 0, 0, gateStatus, reasons, "admin", Instant.now(),
                status.terminal() ? Instant.now() : null);
    }
}
