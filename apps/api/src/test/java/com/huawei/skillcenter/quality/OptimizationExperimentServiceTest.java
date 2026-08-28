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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizationExperimentServiceTest {
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
        when(evaluations.resolveSuite("smoke", "smoke-v1"))
                .thenReturn(new EvaluationSuite("smoke", "smoke-v1", List.of(
                        new EvaluationCase("case-1", "case"))));
        service = new OptimizationExperimentService(store, workItems, evaluations, benchmarks, governance,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsOnlyOneActiveExperimentAndPinsTheWorkItemSuite() {
        OptimizationWorkItem ready = readyWorkItem();
        when(workItems.find("work-1", admin)).thenReturn(ready);
        when(store.findActiveByWorkItemId("work-1")).thenReturn(Optional.empty());
        when(store.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.replace(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(evaluations.submit(any())).thenReturn(queuedRun("experiment-1"));

        OptimizationExperiment first = service.create(new OptimizationExperimentCreateRequest("work-1"), admin, "req-1");
        when(store.findActiveByWorkItemId("work-1")).thenReturn(Optional.of(first));

        OptimizationExperiment second = service.create(new OptimizationExperimentCreateRequest("work-1"), admin, "req-2");

        assertThat(first.status()).isEqualTo(OptimizationExperimentStatus.RUNNING);
        assertThat(first.suiteId()).isEqualTo("smoke");
        assertThat(first.suiteVersion()).isEqualTo("smoke-v1");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void reconcileCompletionBindsSnapshotButDoesNotCompleteWorkItem() {
        OptimizationWorkItem ready = readyWorkItem();
        OptimizationExperiment running = experiment(OptimizationExperimentStatus.RUNNING, "run-1", "", "", "");
        when(store.find("experiment-1")).thenReturn(Optional.of(running));
        when(evaluations.find("run-1")).thenReturn(completedRun("experiment-1"));
        when(evaluations.findSnapshot("run-1")).thenReturn(snapshot());
        when(workItems.find("work-1", admin)).thenReturn(ready);
        when(workItems.bindEvidence(any(), any(), any(), any())).thenReturn(ready);
        when(store.replace(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OptimizationExperiment completed = service.reconcile("experiment-1", admin, "req-3");

        assertThat(completed.status()).isEqualTo(OptimizationExperimentStatus.COMPLETED);
        assertThat(completed.qualitySnapshotId()).isEqualTo("run-1");
        verify(workItems).bindEvidence("work-1",
                new OptimizationWorkItemEvidenceRequest(OptimizationWorkItem.QUALITY_SNAPSHOT, "run-1", ""),
                admin, "req-3");
        assertThat(ready.status()).isEqualTo(OptimizationWorkItemStatus.READY_FOR_EVALUATION);
    }

    @Test
    void reconcileFailureRecordsStableFailureCode() {
        OptimizationExperiment running = experiment(OptimizationExperimentStatus.RUNNING, "run-1", "", "", "");
        when(store.find("experiment-1")).thenReturn(Optional.of(running));
        when(evaluations.find("run-1")).thenReturn(new EvaluationRun("run-1", "skill-a", "1.1.0",
                "smoke", "smoke-v1", EvaluationRunStatus.FAILED, "runner", "provider", "mock",
                NOW, NOW, 2, 0, 0, "EVALUATION_EXECUTION_FAILED", QualityGateStatus.BLOCKED,
                List.of("EVALUATION_EXECUTION_FAILED"), "", "", "", "experiment-1"));
        when(store.replace(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OptimizationExperiment failed = service.reconcile("experiment-1", admin, "req-4");

        assertThat(failed.status()).isEqualTo(OptimizationExperimentStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("EVALUATION_EXECUTION_FAILED");
    }

    @Test
    void cancelBeforeSubmissionDoesNotRequireAnEvaluationRun() {
        OptimizationExperiment queued = experiment(OptimizationExperimentStatus.QUEUED, "", "", "", "");
        when(store.find("experiment-1")).thenReturn(Optional.of(queued));
        when(store.replace(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OptimizationExperiment cancelled = service.cancel("experiment-1", admin, "req-5");

        assertThat(cancelled.status()).isEqualTo(OptimizationExperimentStatus.CANCELLED);
        assertThat(cancelled.failureCode()).isEqualTo("CANCELLED_BY_REQUEST");
    }

    @Test
    void rejectsBenchmarkWhenExperimentIsNotCompleted() {
        OptimizationExperiment running = experiment(OptimizationExperimentStatus.RUNNING, "run-1", "", "", "");
        when(store.find("experiment-1")).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> service.benchmark("experiment-1",
                new OptimizationExperimentBenchmarkRequest("24h"), admin, "req-6"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMPLETED");
    }

    private OptimizationExperiment experiment(String status, String runId, String snapshotId,
                                              String benchmarkId, String failureCode) {
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "mock", "", "", "", "smoke", "smoke-v1", status, runId, snapshotId,
                benchmarkId, failureCode, "admin", NOW, "admin", NOW);
    }

    private OptimizationWorkItem readyWorkItem() {
        return new OptimizationWorkItem("work-1", "skill-a", "1.0.0", "suggestion-1", "Suggestion",
                "LATENCY", "MEDIUM", List.of("p95Ms=1200"), "hypothesis", "owner-a",
                OptimizationWorkItemStatus.READY_FOR_EVALUATION, "1.1.0", OptimizationWorkItem.NONE, "", "",
                "mock", "", "", "", "smoke", "smoke-v1", "admin", NOW, "admin", NOW);
    }

    private EvaluationRun queuedRun(String experimentId) {
        return new EvaluationRun("run-1", "skill-a", "1.1.0", "smoke", "smoke-v1",
                EvaluationRunStatus.QUEUED, "runner", "provider", "mock", NOW, null, 2, 0, 0, "",
                QualityGateStatus.BLOCKED, List.of("EVALUATION_NOT_COMPLETED"), "", "", "", experimentId);
    }

    private EvaluationRun completedRun(String experimentId) {
        return new EvaluationRun("run-1", "skill-a", "1.1.0", "smoke", "smoke-v1",
                EvaluationRunStatus.COMPLETED, "runner", "provider", "mock", NOW, NOW, 2, 2, 100, "",
                QualityGateStatus.PASSED, List.of(), "", "", "", experimentId);
    }

    private QualitySnapshot snapshot() {
        return new QualitySnapshot("run-1", "skill-a", "1.1.0", "smoke", "smoke-v1", "runner",
                "provider", "mock", NOW, 100, 2, 2, true, "rules-v1", 100, 1.0,
                QualityGateStatus.PASSED, List.of(), "", "", "");
    }
}
