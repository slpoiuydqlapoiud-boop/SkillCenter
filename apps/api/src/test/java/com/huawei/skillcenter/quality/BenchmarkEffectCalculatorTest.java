package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkEffectCalculatorTest {
    private final BenchmarkEffectCalculator calculator = new BenchmarkEffectCalculator();

    @Test
    void classifiesImprovementWhenAllObservedSignalsMoveInTheRightDirection() {
        QualityComparison comparison = comparison(true, new QualityComparison.Delta(8, 3, .1, 2.5, -120, 4));
        assertThat(calculator.classify(comparison)).isEqualTo("IMPROVED");
    }

    @Test
    void distinguishesRegressionMixedNoChangeAndNotComparable() {
        assertThat(calculator.classify(comparison(true, new QualityComparison.Delta(-1, 0, 0, 0, 0, 0))))
                .isEqualTo("REGRESSED");
        assertThat(calculator.classify(comparison(true, new QualityComparison.Delta(4, 0, -.1, 0, 0, 0))))
                .isEqualTo("MIXED");
        assertThat(calculator.classify(comparison(true, new QualityComparison.Delta(0, 0, 0, 0, 0, 0))))
                .isEqualTo("NO_CHANGE");
        assertThat(calculator.classify(comparison(false, null))).isEqualTo("NOT_COMPARABLE");
    }

    private QualityComparison comparison(boolean comparable, QualityComparison.Delta delta) {
        QualityComparison.Metric metric = new QualityComparison.Metric(90, 90, .9, 95, 1000, 10,
                "PASSED", "mock", "smoke-v1", "quality-v1", "mock-runner", "mock-eval", Instant.now());
        return new QualityComparison("skill-a", "1.0.0", "1.1.0", comparable,
                comparable ? "COMPARABLE" : "NO_COMPARABLE_SNAPSHOT", "reason", metric, metric, delta);
    }
}
