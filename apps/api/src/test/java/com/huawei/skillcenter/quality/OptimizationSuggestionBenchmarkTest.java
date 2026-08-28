package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.operations.RuntimeOperationsQuery;
import com.huawei.skillcenter.operations.RuntimeOperationsService;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizationSuggestionBenchmarkTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesRegressionBenchmarkAsActionableSuggestion() {
        QualityEvaluationService quality = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        BenchmarkService benchmarks = mock(BenchmarkService.class);
        when(quality.snapshots("skill-a")).thenReturn(List.of());
        when(runtime.snapshot(any(RuntimeOperationsQuery.class))).thenReturn(new RuntimeOperationsSnapshot(
                "24h", Instant.now(), "mock", new RuntimeOperationsSnapshot.Filters("skill-a", "1.0.0", null),
                RuntimeOperationsSnapshot.Totals.empty(), RuntimeOperationsSnapshot.Latency.empty(), List.of(), List.of(), List.of(), List.of()));
        QualityComparison comparison = new QualityComparison("skill-a", "0.9.0", "1.0.0", true,
                "COMPARABLE", "同口径可比", null, null,
                new QualityComparison.Delta(-8, -3, -.1, -2, 120, 0));
        when(benchmarks.list("skill-a")).thenReturn(List.of(new BenchmarkResult("benchmark-1", "skill-a",
                "0.9.0", "1.0.0", "24h", "mock", "REGRESSED", Instant.now(), comparison)));
        OptimizationSuggestionService service = new OptimizationSuggestionService(
                quality, runtime, new OptimizationSuggestionCalculator(),
                new OptimizationSuggestionDispositionStore(tempDir.resolve("dispositions.json"), new ObjectMapper().findAndRegisterModules()),
                new OptimizationSuggestionThresholdsStore(tempDir.resolve("thresholds.json"), new ObjectMapper().findAndRegisterModules()),
                mock(GovernanceStore.class), benchmarks);

        List<OptimizationSuggestion> suggestions = service.suggestions("skill-a", "1.0.0",
                RuntimeOperationsWindow.TWENTY_FOUR_HOURS, "mock");

        OptimizationSuggestion benchmark = suggestions.stream().filter(item -> item.id().equals("benchmark-effect")).findFirst().orElseThrow();
        assertThat(benchmark.severity()).isEqualTo("HIGH");
        assertThat(benchmark.category()).isEqualTo("BENCHMARK");
        assertThat(benchmark.evidence()).contains("conclusion=REGRESSED", "scoreDelta=-8");
    }

    @Test
    void filtersQualityAndRuntimeEvidenceByExecutionEnvironment() {
        QualityEvaluationService quality = mock(QualityEvaluationService.class);
        RuntimeOperationsService runtime = mock(RuntimeOperationsService.class);
        when(quality.snapshots("skill-a", "openclaw", "mcp-network", "llm-gateway")).thenReturn(List.of(
                new QualitySnapshot("snapshot-env", "skill-a", "1.0.0", "smoke", "suite-v1", "mock-runner",
                        "mock-evaluation", "mock", Instant.now(), 92, 2, 2, true, "quality-v1", 95, 1.0,
                        QualityGateStatus.PASSED, List.of(), "openclaw", "mcp-network", "llm-gateway")));
        when(runtime.snapshot(any(RuntimeOperationsQuery.class))).thenReturn(new RuntimeOperationsSnapshot(
                "24h", Instant.now(), "mock", new RuntimeOperationsSnapshot.Filters("skill-a", "1.0.0", null,
                "openclaw", "mcp-network", "llm-gateway"), RuntimeOperationsSnapshot.Totals.empty(),
                RuntimeOperationsSnapshot.Latency.empty(), List.of(), List.of(), List.of(), List.of()));
        OptimizationSuggestionService service = new OptimizationSuggestionService(
                quality, runtime, new OptimizationSuggestionCalculator(),
                new OptimizationSuggestionDispositionStore(tempDir.resolve("env-dispositions.json"), new ObjectMapper().findAndRegisterModules()),
                new OptimizationSuggestionThresholdsStore(tempDir.resolve("env-thresholds.json"), new ObjectMapper().findAndRegisterModules()),
                mock(GovernanceStore.class));

        service.suggestions("skill-a", "1.0.0", RuntimeOperationsWindow.TWENTY_FOUR_HOURS, "mock",
                "openclaw", "mcp-network", "llm-gateway");

        verify(quality).snapshots("skill-a", "openclaw", "mcp-network", "llm-gateway");
        ArgumentCaptor<RuntimeOperationsQuery> query = ArgumentCaptor.forClass(RuntimeOperationsQuery.class);
        verify(runtime).snapshot(query.capture());
        assertThat(query.getValue().runtimeId()).isEqualTo("openclaw");
        assertThat(query.getValue().mcpServerId()).isEqualTo("mcp-network");
        assertThat(query.getValue().llmProviderId()).isEqualTo("llm-gateway");
    }
}
