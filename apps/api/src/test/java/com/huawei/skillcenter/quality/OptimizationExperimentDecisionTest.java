package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationExperimentDecisionTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final OptimizationExperimentDecisionCalculator calculator = new OptimizationExperimentDecisionCalculator();

    @Test
    void blockedQualityGateRejectsCandidateBeforeBenchmarkConclusion() {
        OptimizationExperimentDecision decision = calculator.calculate(experiment(),
                snapshot(QualityGateStatus.BLOCKED), benchmark("IMPROVED"), "admin", NOW);

        assertThat(decision.decision()).isEqualTo("REJECT_CANDIDATE");
        assertThat(decision.reasonCode()).isEqualTo("QUALITY_GATE_BLOCKED");
        assertThat(decision.qualitySnapshotId()).isEqualTo("snapshot-1");
        assertThat(decision.benchmarkId()).isEqualTo("benchmark-1");
    }

    @Test
    void improvedComparableCandidateCanBeRecommendedForPromotion() {
        OptimizationExperimentDecision decision = calculator.calculate(experiment(),
                snapshot(QualityGateStatus.PASSED), benchmark("IMPROVED"), "admin", NOW);

        assertThat(decision.decision()).isEqualTo("PROMOTE_CANDIDATE");
        assertThat(decision.recommendedAction()).contains("发布审核");
    }

    @Test
    void regressionRejectsCandidate() {
        OptimizationExperimentDecision decision = calculator.calculate(experiment(),
                snapshot(QualityGateStatus.PASSED), benchmark("REGRESSED"), "admin", NOW);

        assertThat(decision.decision()).isEqualTo("REJECT_CANDIDATE");
        assertThat(decision.reasonCode()).isEqualTo("BENCHMARK_REGRESSED");
    }

    @Test
    void mixedAndNoChangeRequireAnotherIteration() {
        assertThat(calculator.calculate(experiment(), snapshot(QualityGateStatus.PASSED), benchmark("MIXED"), "admin", NOW)
                .decision()).isEqualTo("ITERATE");
        assertThat(calculator.calculate(experiment(), snapshot(QualityGateStatus.PASSED), benchmark("NO_CHANGE"), "admin", NOW)
                .decision()).isEqualTo("ITERATE");
    }

    @Test
    void incomparableBenchmarkProducesEvidenceGapDecision() {
        OptimizationExperimentDecision decision = calculator.calculate(experiment(),
                snapshot(QualityGateStatus.PASSED), benchmark("NOT_COMPARABLE"), "admin", NOW);

        assertThat(decision.decision()).isEqualTo("NOT_COMPARABLE");
        assertThat(decision.reasonCode()).isEqualTo("BENCHMARK_NOT_COMPARABLE");
        assertThat(decision.skillId()).isEqualTo("skill-a");
        assertThat(decision.candidateVersion()).isEqualTo("1.1.0");
        assertThat(decision.runtimeId()).isBlank();
    }

    @Test
    void decisionRejectsExecutionContextThatDiffersFromExperiment() {
        OptimizationExperimentDecision decision = new OptimizationExperimentDecision(
                "decision-experiment-1", "experiment-1", "skill-a", "1.0.0", "1.1.0", "production",
                "runtime-a", "", "", "smoke", "smoke-v1", "snapshot-1", "benchmark-1",
                QualityGateStatus.PASSED, "IMPROVED", OptimizationExperimentDecision.PROMOTE_CANDIDATE,
                "BENCHMARK_IMPROVED", "reason", "提交人工发布审核", "admin", NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OptimizationExperiment(
                "experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0", "mock", "", "", "",
                "smoke", "smoke-v1", OptimizationExperimentStatus.COMPLETED, "run-1", "snapshot-1",
                "benchmark-1", "", "admin", NOW, "admin", NOW, decision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision context");
    }

    private OptimizationExperiment experiment() {
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "mock", "", "", "", "smoke", "smoke-v1", OptimizationExperimentStatus.COMPLETED,
                "run-1", "snapshot-1", "benchmark-1", "", "admin", NOW, "admin", NOW);
    }

    private QualitySnapshot snapshot(QualityGateStatus gateStatus) {
        return new QualitySnapshot("snapshot-1", "skill-a", "1.1.0", "smoke", "smoke-v1", "runner",
                "provider", "mock", NOW, 100, 2, 2, true, "rules-v1", 100, 1.0,
                gateStatus, gateStatus == QualityGateStatus.PASSED ? List.of() : List.of("QUALITY_SCORE_BELOW_THRESHOLD"));
    }

    private BenchmarkResult benchmark(String conclusion) {
        return new BenchmarkResult("benchmark-1", "skill-a", "1.0.0", "1.1.0", "24h", "mock",
                conclusion, NOW, null, "", "", "", "smoke", "smoke-v1", "experiment-1");
    }
}
