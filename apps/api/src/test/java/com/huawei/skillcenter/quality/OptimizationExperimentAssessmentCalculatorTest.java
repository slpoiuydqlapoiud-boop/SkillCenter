package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationExperimentAssessmentCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");
    private final OptimizationSuggestionThresholds thresholds = new OptimizationSuggestionThresholds(95, 1_000, 5);
    private final OptimizationExperimentAssessmentCalculator calculator = new OptimizationExperimentAssessmentCalculator();

    @Test
    void identifiesRegressionWhenCandidateLosesSuccessRate() {
        var result = calculator.calculate(observation(5, 4, 1, 80, 100), snapshot(5, 5, 0, 100, 100), thresholds);

        assertThat(result.conclusion()).isEqualTo(OptimizationExperimentAssessment.REGRESSION);
        assertThat(result.reasonCode()).isEqualTo("POST_RELEASE_REGRESSION");
        assertThat(result.recommendedAction()).isEqualTo(OptimizationExperimentAssessment.ROLLBACK_REVIEW);
    }

    @Test
    void identifiesHealthyCandidateWhenBothMetricsMeetBaselineAndThresholds() {
        var result = calculator.calculate(observation(5, 5, 0, 100, 80), snapshot(5, 5, 0, 95, 100), thresholds);

        assertThat(result.conclusion()).isEqualTo(OptimizationExperimentAssessment.HEALTHY);
        assertThat(result.reasonCode()).isEqualTo("POST_RELEASE_HEALTHY");
        assertThat(result.recommendedAction()).isEqualTo(OptimizationExperimentAssessment.KEEP);
    }

    @Test
    void identifiesInsufficientTrafficBeforeComparingMetrics() {
        var result = calculator.calculate(observation(4, 4, 0, 100, 80), snapshot(5, 5, 0, 100, 100), thresholds);

        assertThat(result.conclusion()).isEqualTo(OptimizationExperimentAssessment.INSUFFICIENT_TRAFFIC);
        assertThat(result.reasonCode()).isEqualTo("RUNTIME_SAMPLES_BELOW_MINIMUM");
        assertThat(result.recommendedAction()).isEqualTo(OptimizationExperimentAssessment.CONTINUE_OBSERVING);
    }

    @Test
    void identifiesInconclusiveWhenOneMetricImprovesAndAnotherRegresses() {
        var result = calculator.calculate(observation(5, 5, 0, 100, 180), snapshot(5, 5, 0, 95, 100), thresholds);

        assertThat(result.conclusion()).isEqualTo(OptimizationExperimentAssessment.INCONCLUSIVE);
        assertThat(result.reasonCode()).isEqualTo("POST_RELEASE_INCONCLUSIVE");
        assertThat(result.recommendedAction()).isEqualTo(OptimizationExperimentAssessment.CREATE_FOLLOW_UP);
    }

    @Test
    void rejectsUnknownManualAction() {
        assertThatThrownBy(() -> new OptimizationExperimentAssessment(
                "assessment-1", "experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0", "production",
                "", "", "", "24h", "observation-1", metrics(5, 5, 0, 100, 80), metrics(5, 5, 0, 100, 100),
                95, 1_000, 5, OptimizationExperimentAssessment.HEALTHY, "POST_RELEASE_HEALTHY",
                OptimizationExperimentAssessment.KEEP, "DROP_VERSION", "note", "admin", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    private OptimizationExperimentObservation observation(long total, long successes, long failures,
                                                           double successRate, long p95) {
        return new OptimizationExperimentObservation("observation-1", "experiment-1", "skill-a", "1.1.0",
                "production", "", "", "", "24h", NOW, "admin", total, successes, failures, 0, 0,
                successRate, p95, total == 0 ? "NO_TRAFFIC" : "CAPTURED");
    }

    private RuntimeOperationsSnapshot snapshot(long total, long successes, long failures,
                                               double successRate, long p95) {
        return new RuntimeOperationsSnapshot("24h", NOW, "production", null,
                new RuntimeOperationsSnapshot.Totals(total, successes, failures, 0, 0, successRate),
                new RuntimeOperationsSnapshot.Latency(total, p95, p95, p95), List.of(), List.of(), List.of(), List.of());
    }

    private OptimizationExperimentAssessment.Metrics metrics(long total, long successes, long failures,
                                                              double successRate, long p95) {
        return new OptimizationExperimentAssessment.Metrics(total, successes, failures, 0, 0, successRate, p95, NOW);
    }
}
