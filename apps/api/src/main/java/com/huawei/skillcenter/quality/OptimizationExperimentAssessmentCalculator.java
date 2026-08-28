package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;

public class OptimizationExperimentAssessmentCalculator {
    public Result calculate(OptimizationExperimentObservation candidateObservation,
                            RuntimeOperationsSnapshot baselineSnapshot,
                            OptimizationSuggestionThresholds thresholds) {
        if (candidateObservation == null) throw new IllegalArgumentException("candidate observation is required");
        if (baselineSnapshot == null) throw new IllegalArgumentException("baseline snapshot is required");
        if (thresholds == null) throw new IllegalArgumentException("thresholds are required");
        OptimizationExperimentAssessment.Metrics candidate = new OptimizationExperimentAssessment.Metrics(
                candidateObservation.totalCalls(), candidateObservation.successfulCalls(), candidateObservation.failures(),
                candidateObservation.timeouts(), candidateObservation.cancellations(), candidateObservation.successRate(),
                candidateObservation.p95Ms(), candidateObservation.capturedAt());
        RuntimeOperationsSnapshot.Totals totals = baselineSnapshot.totals();
        RuntimeOperationsSnapshot.Latency latency = baselineSnapshot.latency();
        OptimizationExperimentAssessment.Metrics baseline = new OptimizationExperimentAssessment.Metrics(
                totals.total(), totals.successes(), totals.failures(), totals.timeouts(), totals.cancellations(),
                totals.successRate(), latency.p95Ms(), baselineSnapshot.generatedAt());

        if (candidate.totalCalls() < thresholds.minRuntimeSamples()
                || baseline.totalCalls() < thresholds.minRuntimeSamples()) {
            return new Result(candidate, baseline, OptimizationExperimentAssessment.INSUFFICIENT_TRAFFIC,
                    "RUNTIME_SAMPLES_BELOW_MINIMUM", OptimizationExperimentAssessment.CONTINUE_OBSERVING);
        }
        boolean regressed = candidate.successRate() < baseline.successRate()
                || candidate.p95Ms() > baseline.p95Ms()
                || candidate.successRate() < thresholds.minSuccessRatePercent()
                || candidate.p95Ms() > thresholds.maxP95Ms();
        boolean improved = candidate.successRate() > baseline.successRate()
                || candidate.p95Ms() < baseline.p95Ms();
        if (regressed && improved) {
            return new Result(candidate, baseline, OptimizationExperimentAssessment.INCONCLUSIVE,
                    "POST_RELEASE_INCONCLUSIVE", OptimizationExperimentAssessment.CREATE_FOLLOW_UP);
        }
        if (regressed) {
            return new Result(candidate, baseline, OptimizationExperimentAssessment.REGRESSION,
                    "POST_RELEASE_REGRESSION", OptimizationExperimentAssessment.ROLLBACK_REVIEW);
        }
        if (improved) {
            return new Result(candidate, baseline, OptimizationExperimentAssessment.HEALTHY,
                    "POST_RELEASE_HEALTHY", OptimizationExperimentAssessment.KEEP);
        }
        return new Result(candidate, baseline, OptimizationExperimentAssessment.INCONCLUSIVE,
                "POST_RELEASE_INCONCLUSIVE", OptimizationExperimentAssessment.CREATE_FOLLOW_UP);
    }

    public record Result(OptimizationExperimentAssessment.Metrics candidate,
                         OptimizationExperimentAssessment.Metrics baseline,
                         String conclusion, String reasonCode, String recommendedAction) {
    }
}
