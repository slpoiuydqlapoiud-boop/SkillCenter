package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationSuggestionCalculatorTest {
    private final OptimizationSuggestionCalculator calculator = new OptimizationSuggestionCalculator();

    @Test
    void explainsBlockedQualityAndLowRuntimeReliabilityWithTraceableEvidence() {
        QualitySnapshot snapshot = snapshot(QualityGateStatus.BLOCKED, 62, 0.5);
        RuntimeOperationsSnapshot runtime = runtime(20, 16, 1_800, "UPSTREAM_TIMEOUT");

        List<OptimizationSuggestion> suggestions = calculator.calculate("skill-a", "1.0.0", snapshot, runtime);

        assertThat(suggestions).extracting(OptimizationSuggestion::category)
                .containsExactly("QUALITY_GATE", "RELIABILITY", "LATENCY");
        assertThat(suggestions.get(0).severity()).isEqualTo("HIGH");
        assertThat(suggestions.get(0).evidence()).contains("gateStatus=BLOCKED", "score=62");
        assertThat(suggestions.get(1).evidence()).contains("successRate=80.0%", "error=UPSTREAM_TIMEOUT");
        assertThat(suggestions.get(2).evidence()).contains("p95Ms=1800");
        assertThat(suggestions.get(0).recommendedAction()).isNotBlank();
    }

    @Test
    void returnsExplicitEvidenceGapsWhenQualityOrRuntimeDataIsMissing() {
        List<OptimizationSuggestion> suggestions = calculator.calculate("skill-a", "1.0.0", null, runtime(0, 0, 0, null));

        assertThat(suggestions).extracting(OptimizationSuggestion::category)
                .containsExactly("QUALITY_DATA", "RUNTIME_DATA");
        assertThat(suggestions).allSatisfy(suggestion -> {
            assertThat(suggestion.severity()).isEqualTo("INFO");
            assertThat(suggestion.evidence()).isNotEmpty();
        });
    }

    @Test
    void returnsNoSuggestionWhenQualityAndRuntimeAreHealthy() {
        List<OptimizationSuggestion> suggestions = calculator.calculate(
                "skill-a", "1.0.0", snapshot(QualityGateStatus.PASSED, 96, 1.0), runtime(20, 20, 120, null));

        assertThat(suggestions).isEmpty();
    }

    private QualitySnapshot snapshot(QualityGateStatus gate, int score, double passRate) {
        return new QualitySnapshot("snapshot-1", "skill-a", "1.0.0", "smoke", "smoke-v1",
                "mock-runner", "mock-evaluation", "mock", Instant.parse("2026-08-21T00:00:00Z"),
                score, 2, passRate == 1.0 ? 2 : 1, true, "quality-v1", 95, passRate, gate,
                gate == QualityGateStatus.PASSED ? List.of() : List.of("MIN_SCORE_NOT_MET"));
    }

    private RuntimeOperationsSnapshot runtime(long total, long successes, long p95, String errorCode) {
        return new RuntimeOperationsSnapshot("24h", Instant.parse("2026-08-21T00:00:00Z"), "production",
                new RuntimeOperationsSnapshot.Filters("skill-a", "1.0.0", null),
                new RuntimeOperationsSnapshot.Totals(total, successes, total - successes, 0, 0,
                        total == 0 ? 0 : (double) successes / total * 100),
                new RuntimeOperationsSnapshot.Latency(total, 100, p95, p95),
                errorCode == null ? List.of() : List.of(new RuntimeOperationsSnapshot.ErrorCount(errorCode, total - successes,
                        total == 0 ? 0 : (double) (total - successes) / total * 100)),
                List.of(), List.of(), List.of());
    }
}
