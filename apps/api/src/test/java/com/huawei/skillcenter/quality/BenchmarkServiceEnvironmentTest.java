package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BenchmarkServiceEnvironmentTest {
    @Test
    void benchmarkCarriesExecutionEnvironmentIntoComparisonAndResult() throws Exception {
        QualityComparisonService comparisonService = mock(QualityComparisonService.class);
        BenchmarkStore store = mock(BenchmarkStore.class);
        GovernanceStore governanceStore = mock(GovernanceStore.class);
        QualityComparison comparison = new QualityComparison("skill-a", "1.0.0", "1.1.0", true,
                "COMPARABLE", "同口径可比", null, null,
                new QualityComparison.Delta(4, 2, .1, 2, -40, 2));
        when(comparisonService.compare("skill-a", "1.0.0", "1.1.0", RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                "mock", "openclaw", "mcp-network", "llm-gateway")).thenReturn(comparison);
        BenchmarkService service = new BenchmarkService(comparisonService, new BenchmarkEffectCalculator(), store,
                governanceStore);

        BenchmarkResult result = service.run(new BenchmarkRequest("skill-a", "1.0.0", "1.1.0", "24h", "mock",
                        "openclaw", "mcp-network", "llm-gateway"), RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                new Actor("benchmark-admin", "admin"), "request-1");

        assertThat(result.runtimeId()).isEqualTo("openclaw");
        assertThat(result.mcpServerId()).isEqualTo("mcp-network");
        assertThat(result.llmProviderId()).isEqualTo("llm-gateway");
        verify(comparisonService).compare("skill-a", "1.0.0", "1.1.0", RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                "mock", "openclaw", "mcp-network", "llm-gateway");
    }

    @Test
    void benchmarkCarriesExactSuiteContextIntoComparisonAndResult() throws Exception {
        QualityComparisonService comparisonService = mock(QualityComparisonService.class);
        BenchmarkStore store = mock(BenchmarkStore.class);
        GovernanceStore governanceStore = mock(GovernanceStore.class);
        QualityComparison comparison = new QualityComparison("skill-a", "1.0.0", "1.1.0", true,
                "COMPARABLE", "同口径可比", null, null,
                new QualityComparison.Delta(4, 2, .1, 2, -40, 2));
        when(comparisonService.compare("skill-a", "1.0.0", "1.1.0", RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                "mock", "", "", "", "release", "release-v2")).thenReturn(comparison);
        BenchmarkService service = new BenchmarkService(comparisonService, new BenchmarkEffectCalculator(), store,
                governanceStore);

        BenchmarkResult result = service.run(new BenchmarkRequest("skill-a", "1.0.0", "1.1.0", "24h", "mock",
                        "", "", "", "release", "release-v2"), RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                new Actor("benchmark-admin", "admin"), "request-suite");

        assertThat(result.suiteId()).isEqualTo("release");
        assertThat(result.suiteVersion()).isEqualTo("release-v2");
        verify(comparisonService).compare("skill-a", "1.0.0", "1.1.0", RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                "mock", "", "", "", "release", "release-v2");
    }

    @Test
    void benchmarkHistoryFiltersByExecutionEnvironmentAndRejectsUnsafeFilters() {
        BenchmarkStore store = mock(BenchmarkStore.class);
        when(store.findAll("skill-a")).thenReturn(java.util.List.of(
                new BenchmarkResult("b-env", "skill-a", "1.0.0", "1.1.0", "24h", "mock", "IMPROVED",
                        null, null, "openclaw", "mcp-network", "llm-gateway"),
                new BenchmarkResult("b-legacy", "skill-a", "1.0.0", "1.1.0", "24h", "mock", "IMPROVED",
                        null, null)));
        BenchmarkService service = new BenchmarkService(mock(QualityComparisonService.class),
                new BenchmarkEffectCalculator(), store, mock(GovernanceStore.class));

        assertThat(service.list("skill-a", "openclaw", "mcp-network", "llm-gateway"))
                .extracting(BenchmarkResult::benchmarkId).containsExactly("b-env");
        assertThatThrownBy(() -> service.list("skill-a", "customer prompt", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeId");
    }

    @Test
    void benchmarkHistoryFiltersByDataSourceBeforeEnvironmentFilters() {
        BenchmarkStore store = mock(BenchmarkStore.class);
        when(store.findAll("skill-a")).thenReturn(java.util.List.of(
                new BenchmarkResult("b-mock", "skill-a", "1.0.0", "1.1.0", "24h", "mock", "IMPROVED",
                        null, null),
                new BenchmarkResult("b-production", "skill-a", "1.0.0", "1.1.0", "24h", "production", "IMPROVED",
                        null, null)));
        BenchmarkService service = new BenchmarkService(mock(QualityComparisonService.class),
                new BenchmarkEffectCalculator(), store, mock(GovernanceStore.class));

        assertThat(service.list("skill-a", "production", null, null, null))
                .extracting(BenchmarkResult::benchmarkId).containsExactly("b-production");
    }

    @Test
    void benchmarkReusesTheSameResultForAnExperimentId() {
        QualityComparisonService comparisonService = mock(QualityComparisonService.class);
        BenchmarkStore store = mock(BenchmarkStore.class);
        GovernanceStore governanceStore = mock(GovernanceStore.class);
        QualityComparison comparison = new QualityComparison("skill-a", "1.0.0", "1.1.0", true,
                "COMPARABLE", "same context", null, null,
                new QualityComparison.Delta(4, 2, .1, 2, -40, 2));
        when(comparisonService.compare("skill-a", "1.0.0", "1.1.0", RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                "mock", "", "", "")).thenReturn(comparison);
        when(store.findByExperimentId("experiment-1")).thenReturn(null);
        BenchmarkService service = new BenchmarkService(comparisonService, new BenchmarkEffectCalculator(), store,
                governanceStore);
        BenchmarkRequest request = new BenchmarkRequest("skill-a", "1.0.0", "1.1.0", "24h", "mock",
                "", "", "", "", "", "experiment-1");

        BenchmarkResult first = service.run(request, RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                new Actor("benchmark-admin", "admin"), "request-1");
        when(store.findByExperimentId("experiment-1")).thenReturn(first);

        BenchmarkResult second = service.run(request, RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                new Actor("benchmark-admin", "admin"), "request-2");

        assertThat(second.benchmarkId()).isEqualTo(first.benchmarkId());
        verify(store).add(first);
    }

    @Test
    void benchmarkRejectsExperimentIdReuseWhenContextDiffers() {
        BenchmarkStore store = mock(BenchmarkStore.class);
        BenchmarkResult existing = new BenchmarkResult("benchmark-1", "skill-a", "1.0.0", "1.1.0",
                "24h", "mock", "IMPROVED", null, null, "", "", "", "", "", "experiment-1");
        when(store.findByExperimentId("experiment-1")).thenReturn(existing);
        BenchmarkService service = new BenchmarkService(mock(QualityComparisonService.class),
                new BenchmarkEffectCalculator(), store, mock(GovernanceStore.class));

        assertThatThrownBy(() -> service.run(new BenchmarkRequest("skill-a", "1.0.0", "1.2.0", "24h", "mock",
                        "", "", "", "", "", "experiment-1"), RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                new Actor("benchmark-admin", "admin"), "request-3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("experimentId");
    }
}
