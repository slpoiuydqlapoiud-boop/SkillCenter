package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizationExperimentDecisionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final Actor admin = new Actor("admin", "admin");
    private OptimizationExperimentStore store;
    private OptimizationWorkItemService workItems;
    private QualityEvaluationService evaluations;
    private BenchmarkService benchmarks;
    private GovernanceStore governance;
    private OptimizationExperimentService service;

    @BeforeEach
    void setUp() {
        store = mock(OptimizationExperimentStore.class);
        workItems = mock(OptimizationWorkItemService.class);
        evaluations = mock(QualityEvaluationService.class);
        benchmarks = mock(BenchmarkService.class);
        governance = mock(GovernanceStore.class);
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(new SkillVersion(
                "pkg-1", "skill-a", "1.1.0", "published", "sha", 1, "artifact", "owner",
                NOW, "admin", NOW, "review-1")), List.of(), List.of(), List.of()));
        service = new OptimizationExperimentService(store, workItems, evaluations, benchmarks, governance,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void decidePersistsOneDecisionAndSecondCallReturnsTheSameSnapshot() {
        OptimizationExperiment completed = experiment();
        AtomicReference<OptimizationExperiment> stored = new AtomicReference<>(completed);
        when(store.find("experiment-1")).thenAnswer(invocation -> Optional.of(stored.get()));
        when(evaluations.findSnapshot("snapshot-1")).thenReturn(snapshot());
        when(benchmarks.list(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(benchmark()));
        when(store.replace(any())).thenAnswer(invocation -> {
            OptimizationExperiment value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });

        OptimizationExperiment first = service.decide("experiment-1", admin, "req-1");
        OptimizationExperiment second = service.decide("experiment-1", admin, "req-2");

        assertThat(first.decision()).isNotNull();
        assertThat(second.decision()).isEqualTo(first.decision());
        assertThat(second.decision().evaluatedAt()).isEqualTo(NOW);
        verify(store, times(1)).replace(any());
    }

    @Test
    void decideRejectsCompletedExperimentWithoutBenchmarkEvidence() {
        OptimizationExperiment completed = experiment();
        when(store.find("experiment-1")).thenReturn(Optional.of(withoutBenchmark(completed)));

        assertThatThrownBy(() -> service.decide("experiment-1", admin, "req-3"))
                .isInstanceOf(OptimizationExperimentDecisionInvalidStateException.class)
                .hasMessageContaining("Benchmark");
    }

    @Test
    void decideRejectsBenchmarkWithDifferentSkillContext() {
        when(store.find("experiment-1")).thenReturn(Optional.of(experiment()));
        when(evaluations.findSnapshot("snapshot-1")).thenReturn(snapshot());
        BenchmarkResult mismatched = new BenchmarkResult("benchmark-1", "skill-other", "1.0.0", "1.1.0",
                "24h", "mock", "IMPROVED", NOW, null, "", "", "", "smoke", "smoke-v1", "experiment-1");
        when(benchmarks.list(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(mismatched));

        assertThatThrownBy(() -> service.decide("experiment-1", admin, "req-4"))
                .isInstanceOf(OptimizationExperimentDecisionInvalidStateException.class)
                .hasMessageContaining("context");
    }

    private OptimizationExperiment experiment() {
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "mock", "", "", "", "smoke", "smoke-v1", OptimizationExperimentStatus.COMPLETED,
                "run-1", "snapshot-1", "benchmark-1", "", "admin", NOW, "admin", NOW);
    }

    private OptimizationExperiment withoutBenchmark(OptimizationExperiment value) {
        return new OptimizationExperiment(value.experimentId(), value.workItemId(), value.skillId(),
                value.sourceVersion(), value.candidateVersion(), value.dataSource(), value.runtimeId(),
                value.mcpServerId(), value.llmProviderId(), value.suiteId(), value.suiteVersion(), value.status(),
                value.evaluationRunId(), value.qualitySnapshotId(), "", value.failureCode(), value.createdBy(),
                value.createdAt(), value.updatedBy(), value.updatedAt());
    }

    private OptimizationExperiment withDecision(OptimizationExperiment value, OptimizationExperimentDecision decision) {
        return new OptimizationExperiment(value.experimentId(), value.workItemId(), value.skillId(),
                value.sourceVersion(), value.candidateVersion(), value.dataSource(), value.runtimeId(),
                value.mcpServerId(), value.llmProviderId(), value.suiteId(), value.suiteVersion(), value.status(),
                value.evaluationRunId(), value.qualitySnapshotId(), value.benchmarkId(), value.failureCode(),
                value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt(), decision);
    }

    private QualitySnapshot snapshot() {
        return new QualitySnapshot("snapshot-1", "skill-a", "1.1.0", "smoke", "smoke-v1", "runner",
                "provider", "mock", NOW, 100, 2, 2, true, "rules-v1", 100, 1.0,
                QualityGateStatus.PASSED, List.of());
    }

    private BenchmarkResult benchmark() {
        return new BenchmarkResult("benchmark-1", "skill-a", "1.0.0", "1.1.0", "24h", "mock",
                "IMPROVED", NOW, null, "", "", "", "smoke", "smoke-v1", "experiment-1");
    }
}
