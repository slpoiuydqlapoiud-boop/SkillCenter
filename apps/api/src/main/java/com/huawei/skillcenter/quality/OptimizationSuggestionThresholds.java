package com.huawei.skillcenter.quality;

/** Department-level limits used only to calculate optimization signals. */
public record OptimizationSuggestionThresholds(
        double minSuccessRatePercent,
        long maxP95Ms,
        long minRuntimeSamples
) {
    public OptimizationSuggestionThresholds {
        if (!Double.isFinite(minSuccessRatePercent) || minSuccessRatePercent < 0 || minSuccessRatePercent > 100) {
            throw new IllegalArgumentException("minSuccessRatePercent must be between 0 and 100");
        }
        if (maxP95Ms < 1 || maxP95Ms > 600_000) {
            throw new IllegalArgumentException("maxP95Ms must be between 1 and 600000");
        }
        if (minRuntimeSamples < 1 || minRuntimeSamples > 1_000_000) {
            throw new IllegalArgumentException("minRuntimeSamples must be between 1 and 1000000");
        }
    }

    public static OptimizationSuggestionThresholds defaults() {
        return new OptimizationSuggestionThresholds(95.0, 1_000, 5);
    }
}
