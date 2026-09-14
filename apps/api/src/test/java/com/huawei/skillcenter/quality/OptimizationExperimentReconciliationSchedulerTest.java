package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.OptimizationExperimentBackendHealth;
import com.huawei.skillcenter.operations.OptimizationExperimentBackendReadiness;
import com.huawei.skillcenter.operations.OptimizationExperimentReconciliationScheduler;
import com.huawei.skillcenter.operations.BenchmarkBackendHealth;
import com.huawei.skillcenter.operations.BenchmarkBackendReadiness;
import com.huawei.skillcenter.operations.OptimizationWorkItemBackendHealth;
import com.huawei.skillcenter.operations.OptimizationWorkItemBackendReadiness;
import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

class OptimizationExperimentReconciliationSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void schedulerRequiresSharedReadyExperimentAndWorkItemBackends() {
        OptimizationExperimentService service = mock(OptimizationExperimentService.class);
        OptimizationExperimentRepository experiments = mock(OptimizationExperimentRepository.class);
        OptimizationExperimentBackendHealth experimentBackend = () -> new OptimizationExperimentBackendReadiness(
                "json", "DEGRADED", "OPTIMIZATION_EXPERIMENT_JSON_ONLY", "本地实验状态");
        OptimizationWorkItemBackendHealth workItemBackend = () -> new OptimizationWorkItemBackendReadiness(
                "postgresql", "READY", "OPTIMIZATION_WORK_ITEM_POSTGRES_READY", "工作项已就绪");

        assertThatThrownBy(() -> new OptimizationExperimentReconciliationScheduler(service, experiments,
                experimentBackend, workItemBackend, readyProperties(), readyPersistence()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("optimization experiment scheduler requires READY shared experiment backend");
    }

    @Test
    void schedulerAcceptsDepartmentMysqlBackends() {
        OptimizationExperimentService service = mock(OptimizationExperimentService.class);
        OptimizationExperimentRepository experiments = mock(OptimizationExperimentRepository.class);
        OptimizationExperiment running = experiment("mysql-experiment", OptimizationExperimentStatus.RUNNING);
        when(experiments.findAll("", "", "")).thenReturn(List.of(running));

        OptimizationExperimentReconciliationScheduler scheduler = new OptimizationExperimentReconciliationScheduler(
                service, experiments,
                () -> new OptimizationExperimentBackendReadiness(
                        "mysql", "READY", "OPTIMIZATION_EXPERIMENT_MYSQL_READY", "实验已就绪"),
                () -> new OptimizationWorkItemBackendReadiness(
                        "mysql", "READY", "OPTIMIZATION_WORK_ITEM_MYSQL_READY", "工作项已就绪"),
                mysqlProperties(), mysqlPersistence());

        scheduler.runOnce();

        verify(service).reconcile(eq(running.experimentId()), any(),
                eq("scheduler-optimization-experiment-mysql-experiment"));
    }

    @Test
    void automaticActionsRequireReadySharedBenchmarkBackend() {
        OptimizationExperimentService service = mock(OptimizationExperimentService.class);
        OptimizationExperimentRepository experiments = mock(OptimizationExperimentRepository.class);
        BenchmarkBackendHealth benchmarkBackend = () -> new BenchmarkBackendReadiness(
                "json", "DEGRADED", "BENCHMARK_JSON_ONLY", "本地 Benchmark 状态");

        assertThatThrownBy(() -> new OptimizationExperimentReconciliationScheduler(service, experiments,
                readyExperimentBackend(), readyWorkItemBackend(), readyProperties(), readyPersistence(),
                benchmarkBackend, true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("optimization experiment scheduler requires READY shared benchmark backend");
    }

    @Test
    void schedulerReconcilesOnlyActiveExperiments() {
        OptimizationExperimentService service = mock(OptimizationExperimentService.class);
        OptimizationExperimentRepository experiments = mock(OptimizationExperimentRepository.class);
        when(experiments.findAll("", "", "")).thenReturn(List.of(
                experiment("experiment-queued", OptimizationExperimentStatus.QUEUED),
                experiment("experiment-running", OptimizationExperimentStatus.RUNNING),
                experiment("experiment-completed", OptimizationExperimentStatus.COMPLETED)));

        OptimizationExperimentReconciliationScheduler scheduler = new OptimizationExperimentReconciliationScheduler(
                service, experiments, readyExperimentBackend(), readyWorkItemBackend(), readyProperties(), readyPersistence());

        scheduler.runOnce();

        verify(service).reconcile(eq("experiment-queued"), any(), eq("scheduler-optimization-experiment-experiment-queued"));
        verify(service).reconcile(eq("experiment-running"), any(), eq("scheduler-optimization-experiment-experiment-running"));
    }

    @Test
    void oneReconcileFailureDoesNotStopOtherExperiments() {
        OptimizationExperimentService service = mock(OptimizationExperimentService.class);
        OptimizationExperimentRepository experiments = mock(OptimizationExperimentRepository.class);
        when(experiments.findAll("", "", "")).thenReturn(List.of(
                experiment("experiment-failed", OptimizationExperimentStatus.RUNNING),
                experiment("experiment-next", OptimizationExperimentStatus.RUNNING)));
        doThrow(new RuntimeException("provider failure")).when(service)
                .reconcile(eq("experiment-failed"), any(), eq("scheduler-optimization-experiment-experiment-failed"));

        OptimizationExperimentReconciliationScheduler scheduler = new OptimizationExperimentReconciliationScheduler(
                service, experiments, readyExperimentBackend(), readyWorkItemBackend(), readyProperties(), readyPersistence());

        scheduler.runOnce();

        verify(service).reconcile(eq("experiment-next"), any(), eq("scheduler-optimization-experiment-experiment-next"));
    }

    @Test
    void completedExperimentsCanAutomaticallyBenchmarkAndDecide() {
        OptimizationExperimentService service = mock(OptimizationExperimentService.class);
        OptimizationExperimentRepository experiments = mock(OptimizationExperimentRepository.class);
        OptimizationExperiment completed = experiment("experiment-completed", OptimizationExperimentStatus.COMPLETED);
        OptimizationExperiment benchmarked = new OptimizationExperiment(
                completed.experimentId(), completed.workItemId(), completed.skillId(), completed.sourceVersion(),
                completed.candidateVersion(), completed.dataSource(), completed.runtimeId(), completed.mcpServerId(),
                completed.llmProviderId(), completed.suiteId(), completed.suiteVersion(), completed.status(),
                completed.evaluationRunId(), completed.qualitySnapshotId(), "benchmark-experiment-completed", "",
                completed.createdBy(), completed.createdAt(), completed.updatedBy(), completed.updatedAt());
        OptimizationExperiment decided = new OptimizationExperiment(
                benchmarked.experimentId(), benchmarked.workItemId(), benchmarked.skillId(), benchmarked.sourceVersion(),
                benchmarked.candidateVersion(), benchmarked.dataSource(), benchmarked.runtimeId(), benchmarked.mcpServerId(),
                benchmarked.llmProviderId(), benchmarked.suiteId(), benchmarked.suiteVersion(), benchmarked.status(),
                benchmarked.evaluationRunId(), benchmarked.qualitySnapshotId(), benchmarked.benchmarkId(), "",
                benchmarked.createdBy(), benchmarked.createdAt(), benchmarked.updatedBy(), benchmarked.updatedAt(),
                new OptimizationExperimentDecision("decision-1", benchmarked.experimentId(), benchmarked.skillId(),
                        benchmarked.sourceVersion(), benchmarked.candidateVersion(), benchmarked.dataSource(),
                        benchmarked.runtimeId(), benchmarked.mcpServerId(), benchmarked.llmProviderId(),
                        benchmarked.suiteId(), benchmarked.suiteVersion(), benchmarked.qualitySnapshotId(),
                        benchmarked.benchmarkId(), QualityGateStatus.PASSED, "IMPROVED", "PROMOTE_CANDIDATE",
                        "QUALITY_GATE_PASSED", "candidate improved", "REVIEW_RELEASE", "admin", NOW));
        when(experiments.findAll("", "", "")).thenReturn(List.of(completed));
        when(service.reconcile(eq(completed.experimentId()), any(),
                eq("scheduler-optimization-experiment-experiment-completed"))).thenReturn(completed);
        when(service.benchmark(eq(completed.experimentId()), any(), any(),
                eq("scheduler-optimization-experiment-experiment-completed-benchmark"))).thenReturn(benchmarked);
        when(service.decide(eq(completed.experimentId()), any(),
                eq("scheduler-optimization-experiment-experiment-completed-decision"))).thenReturn(decided);

        OptimizationExperimentReconciliationScheduler scheduler = new OptimizationExperimentReconciliationScheduler(
                service, experiments, readyExperimentBackend(), readyWorkItemBackend(), readyProperties(), readyPersistence(),
                readyBenchmarkBackend(), true, true);

        scheduler.runOnce();

        InOrder order = inOrder(service);
        order.verify(service).reconcile(eq(completed.experimentId()), any(),
                eq("scheduler-optimization-experiment-experiment-completed"));
        order.verify(service).benchmark(eq(completed.experimentId()), any(), any(),
                eq("scheduler-optimization-experiment-experiment-completed-benchmark"));
        order.verify(service).decide(eq(completed.experimentId()), any(),
                eq("scheduler-optimization-experiment-experiment-completed-decision"));
    }

    @Test
    void automaticBenchmarkFailureDoesNotStopTheNextExperiment() {
        OptimizationExperimentService service = mock(OptimizationExperimentService.class);
        OptimizationExperimentRepository experiments = mock(OptimizationExperimentRepository.class);
        OptimizationExperiment first = experiment("experiment-first", OptimizationExperimentStatus.COMPLETED);
        OptimizationExperiment second = experiment("experiment-second", OptimizationExperimentStatus.COMPLETED);
        when(experiments.findAll("", "", "")).thenReturn(List.of(first, second));
        when(service.reconcile(eq(first.experimentId()), any(), any())).thenReturn(first);
        when(service.reconcile(eq(second.experimentId()), any(), any())).thenReturn(second);
        doThrow(new RuntimeException("baseline unavailable")).when(service).benchmark(
                eq(first.experimentId()), any(), any(), eq("scheduler-optimization-experiment-experiment-first-benchmark"));

        OptimizationExperimentReconciliationScheduler scheduler = new OptimizationExperimentReconciliationScheduler(
                service, experiments, readyExperimentBackend(), readyWorkItemBackend(), readyProperties(), readyPersistence(),
                readyBenchmarkBackend(), true, true);

        scheduler.runOnce();

        verify(service).reconcile(eq(second.experimentId()), any(),
                eq("scheduler-optimization-experiment-experiment-second"));
    }

    private OptimizationExperimentBackendHealth readyExperimentBackend() {
        return () -> new OptimizationExperimentBackendReadiness(
                "postgresql", "READY", "OPTIMIZATION_EXPERIMENT_POSTGRES_READY", "实验已就绪");
    }

    private OptimizationWorkItemBackendHealth readyWorkItemBackend() {
        return () -> new OptimizationWorkItemBackendReadiness(
                "postgresql", "READY", "OPTIMIZATION_WORK_ITEM_POSTGRES_READY", "工作项已就绪");
    }

    private BenchmarkBackendHealth readyBenchmarkBackend() {
        return () -> new BenchmarkBackendReadiness(
                "postgresql", "READY", "BENCHMARK_POSTGRES_READY", "Benchmark 已就绪");
    }

    private PersistenceControlProperties readyProperties() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        when(properties.normalizedBackend()).thenReturn("postgresql");
        when(properties.normalizedQualityEvidenceBackend()).thenReturn("postgresql");
        return properties;
    }

    private PersistenceBackend readyPersistence() {
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("postgresql", "12", null));
        return persistence;
    }

    private PersistenceControlProperties mysqlProperties() {
        PersistenceControlProperties properties = mock(PersistenceControlProperties.class);
        when(properties.normalizedBackend()).thenReturn("mysql");
        when(properties.normalizedQualityEvidenceBackend()).thenReturn("mysql");
        return properties;
    }

    private PersistenceBackend mysqlPersistence() {
        PersistenceBackend persistence = mock(PersistenceBackend.class);
        when(persistence.status()).thenReturn(PersistenceBackendStatus.ready("mysql", "2", null));
        return persistence;
    }

    private OptimizationExperiment experiment(String id, String status) {
        return new OptimizationExperiment(id, "work-" + id, "skill-a", "1.0.0", "1.1.0", "production",
                "", "", "", "smoke", "smoke-v1", status,
                OptimizationExperimentStatus.QUEUED.equals(status) ? "" : "run-" + id,
                OptimizationExperimentStatus.COMPLETED.equals(status) ? "snapshot-" + id : "", "", "",
                "admin", NOW, "admin", NOW);
    }
}
