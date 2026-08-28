package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationSuggestionThresholdsTest {
    @Test
    void defaultsAreExplicitAndCalculatorUsesConfiguredValues() {
        OptimizationSuggestionThresholds defaults = OptimizationSuggestionThresholds.defaults();
        assertThat(defaults.minSuccessRatePercent()).isEqualTo(95.0);
        assertThat(defaults.maxP95Ms()).isEqualTo(1_000);
        assertThat(defaults.minRuntimeSamples()).isEqualTo(5);
    }

    @Test
    void rejectsUnsafeThresholds() {
        assertThatThrownBy(() -> new OptimizationSuggestionThresholds(-1, 1000, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationSuggestionThresholds(95, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationSuggestionThresholds(95, 1000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
