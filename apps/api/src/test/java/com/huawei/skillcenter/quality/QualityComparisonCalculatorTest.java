package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityComparisonCalculatorTest {
    private final QualityComparisonCalculator calculator = new QualityComparisonCalculator();

    @Test
    void comparesSameEvaluationContextAndCalculatesCandidateDeltas() {
        QualityComparison result = calculator.compare("skill-a", "1.0.0", "1.1.0",
                snapshot("1.0.0", "suite-v1", "quality-v1", 80, 90, .8),
                snapshot("1.1.0", "suite-v1", "quality-v1", 92, 100, 1.0),
                runtime(95, 120, 10), runtime(98, 80, 12));

        assertThat(result.comparable()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("COMPARABLE");
        assertThat(result.delta().score()).isEqualTo(12);
        assertThat(result.delta().passRate()).isEqualTo(.2);
        assertThat(result.delta().successRate()).isEqualTo(3.0);
        assertThat(result.delta().p95Ms()).isEqualTo(-40);
    }

    @Test
    void explainsContextMismatchAndMissingSnapshotsInsteadOfInventingComparison() {
        QualityComparison mismatch = calculator.compare("skill-a", "1.0.0", "1.1.0",
                snapshot("1.0.0", "suite-v1", "quality-v1", 80, 90, .8),
                snapshot("1.1.0", "suite-v2", "quality-v1", 92, 100, 1.0),
                runtime(95, 120, 10), runtime(98, 80, 12));
        assertThat(mismatch.comparable()).isFalse();
        assertThat(mismatch.reasonCode()).isEqualTo("EVALUATION_CONTEXT_MISMATCH");
        assertThat(mismatch.delta()).isNull();

        QualityComparison missing = calculator.compare("skill-a", "1.0.0", "1.1.0", null,
                snapshot("1.1.0", "suite-v1", "quality-v1", 92, 100, 1.0), runtime(0, 0, 0), runtime(98, 80, 12));
        assertThat(missing.comparable()).isFalse();
        assertThat(missing.reasonCode()).isEqualTo("NO_COMPARABLE_SNAPSHOT");

        RuntimeOperationsSnapshot differentWindow = new RuntimeOperationsSnapshot("7d", Instant.parse("2026-08-21T02:00:00Z"), "all",
                new RuntimeOperationsSnapshot.Filters("skill-a", null, null), RuntimeOperationsSnapshot.Totals.empty(),
                RuntimeOperationsSnapshot.Latency.empty(), List.of(), List.of(), List.of(), List.of());
        QualityComparison windowMismatch = calculator.compare("skill-a", "1.0.0", "1.1.0",
                snapshot("1.0.0", "suite-v1", "quality-v1", 80, 90, .8),
                snapshot("1.1.0", "suite-v1", "quality-v1", 92, 100, 1.0),
                runtime(95, 120, 10), differentWindow);
        assertThat(windowMismatch.reasonCode()).isEqualTo("EVALUATION_CONTEXT_MISMATCH");
    }

    @Test
    void refusesToCompareSnapshotsFromDifferentExecutionEnvironments() {
        QualitySnapshot baseline = snapshotWithEnvironment("1.0.0", "openclaw", "mcp-a", "llm-a");
        QualitySnapshot candidate = snapshotWithEnvironment("1.1.0", "openclaw", "mcp-b", "llm-a");

        QualityComparison result = calculator.compare("skill-a", "1.0.0", "1.1.0", baseline, candidate,
                runtime(95, 120, 10), runtime(98, 80, 12));

        assertThat(result.comparable()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("EVALUATION_CONTEXT_MISMATCH");
    }

    private QualitySnapshot snapshot(String version, String suiteVersion, String ruleVersion,
                                     int score, int staticScore, double passRate) {
        return new QualitySnapshot("snapshot-" + version, "skill-a", version, "suite", suiteVersion,
                "mock-runner", "mock-evaluation", "mock", Instant.parse("2026-08-21T02:00:00Z"), score,
                2, passRate == 1.0 ? 2 : 1, true, ruleVersion, staticScore, passRate,
                QualityGateStatus.PASSED, List.of());
    }

    private RuntimeOperationsSnapshot runtime(double successRate, long p95Ms, long total) {
        return new RuntimeOperationsSnapshot("24h", Instant.parse("2026-08-21T02:00:00Z"), "all",
                new RuntimeOperationsSnapshot.Filters("skill-a", null, null),
                new RuntimeOperationsSnapshot.Totals(total, Math.round(total * successRate / 100), 0, 0, 0, successRate),
                new RuntimeOperationsSnapshot.Latency(total, 60, p95Ms, p95Ms), List.of(), List.of(), List.of(), List.of());
    }

    private QualitySnapshot snapshotWithEnvironment(String version, String runtimeId, String mcpServerId,
                                                    String llmProviderId) {
        return new QualitySnapshot("snapshot-" + version, "skill-a", version, "suite", "suite-v1",
                "mock-runner", "mock-evaluation", "mock", Instant.parse("2026-08-21T02:00:00Z"), 90,
                2, 2, true, "quality-v1", 95, 1.0, QualityGateStatus.PASSED, List.of(), runtimeId,
                mcpServerId, llmProviderId);
    }
}
