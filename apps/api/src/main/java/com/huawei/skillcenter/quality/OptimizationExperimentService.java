package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OptimizationExperimentService {
    private final OptimizationExperimentRepository store;
    private final OptimizationWorkItemService workItemService;
    private final QualityEvaluationService evaluationService;
    private final BenchmarkService benchmarkService;
    private final GovernanceStore governanceStore;
    private final Clock clock;
    private final OptimizationExperimentDecisionCalculator decisionCalculator;

    @Autowired
    public OptimizationExperimentService(OptimizationExperimentRepository store,
                                         OptimizationWorkItemService workItemService,
                                         QualityEvaluationService evaluationService,
                                         BenchmarkService benchmarkService,
                                         GovernanceStore governanceStore) {
        this(store, workItemService, evaluationService, benchmarkService, governanceStore, Clock.systemUTC(),
                new OptimizationExperimentDecisionCalculator());
    }

    OptimizationExperimentService(OptimizationExperimentRepository store,
                                  OptimizationWorkItemService workItemService,
                                  QualityEvaluationService evaluationService,
                                  BenchmarkService benchmarkService,
                                  GovernanceStore governanceStore,
                                  Clock clock) {
        this(store, workItemService, evaluationService, benchmarkService, governanceStore, clock,
                new OptimizationExperimentDecisionCalculator());
    }

    OptimizationExperimentService(OptimizationExperimentRepository store,
                                  OptimizationWorkItemService workItemService,
                                  QualityEvaluationService evaluationService,
                                  BenchmarkService benchmarkService,
                                  GovernanceStore governanceStore,
                                  Clock clock,
                                  OptimizationExperimentDecisionCalculator decisionCalculator) {
        this.store = store;
        this.workItemService = workItemService;
        this.evaluationService = evaluationService;
        this.benchmarkService = benchmarkService;
        this.governanceStore = governanceStore;
        this.clock = clock;
        this.decisionCalculator = decisionCalculator;
    }

    public OptimizationExperiment create(OptimizationExperimentCreateRequest request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) throw new IllegalArgumentException("experiment request must not be null");
        OptimizationWorkItem workItem = workItemService.find(request.workItemId(), actor);
        if (!OptimizationWorkItemStatus.READY_FOR_EVALUATION.equals(workItem.status())) {
            throw new OptimizationExperimentInvalidStateException("experiment requires READY_FOR_EVALUATION work item");
        }
        ensureCandidate(workItem.skillId(), workItem.candidateVersion());
        OptimizationExperiment active = store.findActiveByWorkItemId(workItem.workItemId()).orElse(null);
        if (active != null) return active;

        EvaluationSuite suite = resolveSuite(workItem);
        Instant now = clock.instant();
        String experimentId = "experiment-" + UUID.randomUUID();
        OptimizationExperiment queued = new OptimizationExperiment(experimentId, workItem.workItemId(),
                workItem.skillId(), workItem.sourceVersion(), workItem.candidateVersion(), workItem.dataSource(),
                workItem.runtimeId(), workItem.mcpServerId(), workItem.llmProviderId(), suite.id(), suite.version(),
                OptimizationExperimentStatus.QUEUED, "", "", "", "", actor.userId(), now, actor.userId(), now);
        try {
            store.create(queued);
        } catch (OptimizationExperimentConflictException conflict) {
            return store.findActiveByWorkItemId(workItem.workItemId()).orElseThrow(() -> conflict);
        }
        audit("OPTIMIZATION_EXPERIMENT_CREATED", queued, actor, requestId, Map.of());

        try {
            EvaluationRun run = evaluationService.submit(evaluationRequest(queued));
            OptimizationExperiment running = copy(queued, OptimizationExperimentStatus.RUNNING, run.id(),
                    "", "", "", actor);
            store.replace(running);
            audit("OPTIMIZATION_EXPERIMENT_SUBMITTED", running, actor, requestId,
                    Map.of("evaluationRunId", run.id()));
            return running;
        } catch (RuntimeException failure) {
            OptimizationExperiment failed = copy(queued, OptimizationExperimentStatus.FAILED, "", "", "",
                    stableFailureCode(failure), actor);
            store.replace(failed);
            audit("OPTIMIZATION_EXPERIMENT_FAILED", failed, actor, requestId,
                    Map.of("failureCode", failed.failureCode()));
            return failed;
        }
    }

    public List<OptimizationExperiment> list(String skillId, String workItemId, String status, Actor actor) {
        requireAdmin(actor);
        return store.findAll(normalizeOptional(skillId), normalizeOptional(workItemId), normalizeOptional(status));
    }

    public OptimizationExperiment find(String experimentId, Actor actor) {
        requireAdmin(actor);
        return findInternal(experimentId);
    }

    public OptimizationExperiment reconcile(String experimentId, Actor actor, String requestId) {
        requireAdmin(actor);
        OptimizationExperiment current = findInternal(experimentId);
        if (OptimizationExperimentStatus.isTerminal(current.status())) return current;
        if (OptimizationExperimentStatus.QUEUED.equals(current.status())) {
            try {
                EvaluationRun run = evaluationService.submit(evaluationRequest(current));
                current = store.replace(copy(current, OptimizationExperimentStatus.RUNNING, run.id(), "", "", "", actor));
                audit("OPTIMIZATION_EXPERIMENT_SUBMITTED", current, actor, requestId,
                        Map.of("evaluationRunId", run.id()));
            } catch (RuntimeException failure) {
                OptimizationExperiment failed = store.replace(copy(current, OptimizationExperimentStatus.FAILED,
                        "", "", "", stableFailureCode(failure), actor));
                audit("OPTIMIZATION_EXPERIMENT_FAILED", failed, actor, requestId,
                        Map.of("failureCode", failed.failureCode()));
                return failed;
            }
        }

        EvaluationRun run = evaluationService.find(current.evaluationRunId());
        if (run.status() == EvaluationRunStatus.CANCELLED) {
            return store.replace(copy(current, OptimizationExperimentStatus.CANCELLED, run.id(), "", "",
                    "CANCELLED_BY_REQUEST", actor));
        }
        if (run.status() == EvaluationRunStatus.FAILED || run.status() == EvaluationRunStatus.TIMED_OUT) {
            String failureCode = stableFailureCode(run.errorCode());
            OptimizationExperiment failed = store.replace(copy(current, OptimizationExperimentStatus.FAILED,
                    run.id(), "", "", failureCode, actor));
            audit("OPTIMIZATION_EXPERIMENT_FAILED", failed, actor, requestId,
                    Map.of("failureCode", failureCode));
            return failed;
        }
        if (run.status() != EvaluationRunStatus.COMPLETED) return current;

        QualitySnapshot snapshot = evaluationService.findSnapshot(run.id());
        if (snapshot == null) {
            OptimizationExperiment failed = store.replace(copy(current, OptimizationExperimentStatus.FAILED,
                    run.id(), "", "", "QUALITY_SNAPSHOT_NOT_AVAILABLE", actor));
            audit("OPTIMIZATION_EXPERIMENT_FAILED", failed, actor, requestId,
                    Map.of("failureCode", failed.failureCode()));
            return failed;
        }
        ensureSameContext(current, run, snapshot);
        OptimizationWorkItem workItem = workItemService.find(current.workItemId(), actor);
        if (!OptimizationWorkItem.QUALITY_SNAPSHOT.equals(workItem.evidenceType())
                || !run.id().equals(workItem.evidenceId())) {
            workItemService.bindEvidence(current.workItemId(),
                    new OptimizationWorkItemEvidenceRequest(OptimizationWorkItem.QUALITY_SNAPSHOT, run.id(), ""),
                    actor, requestId);
        }
        OptimizationExperiment completed = store.replace(copy(current, OptimizationExperimentStatus.COMPLETED,
                run.id(), run.id(), current.benchmarkId(), "", actor));
        audit("OPTIMIZATION_EXPERIMENT_COMPLETED", completed, actor, requestId,
                Map.of("evaluationRunId", run.id(), "qualitySnapshotId", run.id()));
        audit("OPTIMIZATION_EXPERIMENT_EVIDENCE_BOUND", completed, actor, requestId,
                Map.of("evidenceType", OptimizationWorkItem.QUALITY_SNAPSHOT, "evidenceId", run.id()));
        return completed;
    }

    public OptimizationExperiment cancel(String experimentId, Actor actor, String requestId) {
        requireAdmin(actor);
        OptimizationExperiment current = findInternal(experimentId);
        if (OptimizationExperimentStatus.isTerminal(current.status())) return current;
        if (!current.evaluationRunId().isBlank()) evaluationService.cancel(current.evaluationRunId());
        OptimizationExperiment cancelled = store.replace(copy(current, OptimizationExperimentStatus.CANCELLED,
                current.evaluationRunId(), current.qualitySnapshotId(), current.benchmarkId(),
                "CANCELLED_BY_REQUEST", actor));
        audit("OPTIMIZATION_EXPERIMENT_CANCELLED", cancelled, actor, requestId, Map.of());
        return cancelled;
    }

    public OptimizationExperiment benchmark(String experimentId, OptimizationExperimentBenchmarkRequest request,
                                             Actor actor, String requestId) {
        requireAdmin(actor);
        OptimizationExperiment current = findInternal(experimentId);
        if (!OptimizationExperimentStatus.COMPLETED.equals(current.status())) {
            throw new OptimizationExperimentInvalidStateException("Benchmark requires COMPLETED experiment");
        }
        if (!current.benchmarkId().isBlank()) return current;
        RuntimeOperationsWindow window = RuntimeOperationsWindow.parse(request == null ? "24h" : request.window());
        BenchmarkResult result = benchmarkService.run(new BenchmarkRequest(current.skillId(), current.sourceVersion(),
                current.candidateVersion(), window.label(), current.dataSource(), current.runtimeId(),
                current.mcpServerId(), current.llmProviderId(), current.suiteId(), current.suiteVersion(),
                current.experimentId()), window, actor, requestId);
        OptimizationWorkItem workItem = workItemService.find(current.workItemId(), actor);
        if (!OptimizationWorkItem.BENCHMARK.equals(workItem.evidenceType())
                || !result.benchmarkId().equals(workItem.evidenceId())) {
            workItemService.bindEvidence(current.workItemId(),
                    new OptimizationWorkItemEvidenceRequest(OptimizationWorkItem.BENCHMARK, result.benchmarkId(), ""),
                    actor, requestId);
        }
        OptimizationExperiment updated = store.replace(copy(current, current.status(), current.evaluationRunId(),
                current.qualitySnapshotId(), result.benchmarkId(), "", actor));
        audit("OPTIMIZATION_EXPERIMENT_BENCHMARKED", updated, actor, requestId,
                Map.of("benchmarkId", result.benchmarkId()));
        return updated;
    }

    public OptimizationExperiment decide(String experimentId, Actor actor, String requestId) {
        requireAdmin(actor);
        OptimizationExperiment current = findInternal(experimentId);
        if (current.decision() != null) return current;
        if (!OptimizationExperimentStatus.COMPLETED.equals(current.status())) {
            throw new OptimizationExperimentDecisionInvalidStateException(
                    "decision requires COMPLETED experiment");
        }
        if (current.benchmarkId().isBlank()) {
            throw new OptimizationExperimentDecisionInvalidStateException(
                    "decision requires Benchmark evidence");
        }
        QualitySnapshot snapshot = evaluationService.findSnapshot(current.qualitySnapshotId());
        if (snapshot == null) {
            throw new OptimizationExperimentDecisionInvalidStateException(
                    "quality snapshot is not available for decision");
        }
        BenchmarkResult benchmark = findBenchmark(current);
        if (benchmark == null) {
            throw new OptimizationExperimentDecisionInvalidStateException(
                    "Benchmark evidence is not available for decision");
        }
        ensureDecisionContext(current, snapshot, benchmark);
        OptimizationExperimentDecision decision = decisionCalculator.calculate(current, snapshot, benchmark,
                actor.userId(), clock.instant());
        OptimizationExperiment updated = store.replace(withDecision(current, decision, actor));
        audit("OPTIMIZATION_EXPERIMENT_DECIDED", updated, actor, requestId,
                Map.of("decision", decision.decision(), "reasonCode", decision.reasonCode(),
                        "qualitySnapshotId", decision.qualitySnapshotId(), "benchmarkId", decision.benchmarkId()));
        return updated;
    }

    public OptimizationExperimentDecision findDecision(String experimentId, Actor actor) {
        requireAdmin(actor);
        OptimizationExperiment experiment = findInternal(experimentId);
        if (experiment.decision() == null) {
            throw new OptimizationExperimentDecisionNotFoundException(experimentId);
        }
        return experiment.decision();
    }

    private EvaluationSuite resolveSuite(OptimizationWorkItem item) {
        if (!item.suiteId().isBlank()) return evaluationService.resolveSuite(item.suiteId(), item.suiteVersion());
        return evaluationService.listSuites().stream().filter(EvaluationSuite::enabled).findFirst()
                .orElseThrow(() -> new OptimizationExperimentInvalidStateException("no enabled evaluation suite exists"));
    }

    private EvaluationRequest evaluationRequest(OptimizationExperiment experiment) {
        return new EvaluationRequest(experiment.skillId(), experiment.candidateVersion(), experiment.suiteId(),
                "success", 1_000, experiment.runtimeId(), experiment.mcpServerId(), experiment.llmProviderId(),
                experiment.suiteVersion(), experiment.experimentId());
    }

    private void ensureSameContext(OptimizationExperiment experiment, EvaluationRun run, QualitySnapshot snapshot) {
        if (!experiment.skillId().equals(run.skillId()) || !experiment.candidateVersion().equals(run.skillVersion())
                || !experiment.skillId().equals(snapshot.skillId()) || !experiment.candidateVersion().equals(snapshot.skillVersion())
                || !same(experiment.dataSource(), run.dataSource()) || !same(experiment.dataSource(), snapshot.dataSource())
                || !same(experiment.runtimeId(), run.runtimeId()) || !same(experiment.runtimeId(), snapshot.runtimeId())
                || !same(experiment.mcpServerId(), run.mcpServerId()) || !same(experiment.mcpServerId(), snapshot.mcpServerId())
                || !same(experiment.llmProviderId(), run.llmProviderId()) || !same(experiment.llmProviderId(), snapshot.llmProviderId())
                || !experiment.suiteId().equals(run.suiteId()) || !experiment.suiteVersion().equals(run.suiteVersion())
                || !experiment.suiteId().equals(snapshot.suiteId()) || !experiment.suiteVersion().equals(snapshot.suiteVersion())) {
            throw new OptimizationExperimentInvalidStateException("evaluation evidence does not match experiment context");
        }
    }

    private BenchmarkResult findBenchmark(OptimizationExperiment experiment) {
        return benchmarkService.list(experiment.skillId(), experiment.dataSource(), experiment.runtimeId(),
                        experiment.mcpServerId(), experiment.llmProviderId(), experiment.suiteId(), experiment.suiteVersion())
                .stream()
                .filter(value -> experiment.benchmarkId().equals(value.benchmarkId()))
                .findFirst()
                .orElse(null);
    }

    private void ensureDecisionContext(OptimizationExperiment experiment, QualitySnapshot snapshot,
                                       BenchmarkResult benchmark) {
        boolean snapshotMatches = experiment.skillId().equals(snapshot.skillId())
                && experiment.candidateVersion().equals(snapshot.skillVersion())
                && same(experiment.dataSource(), snapshot.dataSource())
                && same(experiment.runtimeId(), snapshot.runtimeId())
                && same(experiment.mcpServerId(), snapshot.mcpServerId())
                && same(experiment.llmProviderId(), snapshot.llmProviderId())
                && experiment.suiteId().equals(snapshot.suiteId())
                && experiment.suiteVersion().equals(snapshot.suiteVersion())
                && experiment.qualitySnapshotId().equals(snapshot.snapshotId());
        boolean benchmarkMatches = experiment.skillId().equals(benchmark.skillId())
                && experiment.sourceVersion().equals(benchmark.baselineVersion())
                && experiment.candidateVersion().equals(benchmark.candidateVersion())
                && same(experiment.dataSource(), benchmark.dataSource())
                && same(experiment.runtimeId(), benchmark.runtimeId())
                && same(experiment.mcpServerId(), benchmark.mcpServerId())
                && same(experiment.llmProviderId(), benchmark.llmProviderId())
                && experiment.suiteId().equals(benchmark.suiteId())
                && experiment.suiteVersion().equals(benchmark.suiteVersion())
                && experiment.benchmarkId().equals(benchmark.benchmarkId());
        if (!snapshotMatches || !benchmarkMatches) {
            throw new OptimizationExperimentDecisionInvalidStateException(
                    "decision evidence context does not match experiment context");
        }
    }

    private boolean same(String expected, String actual) {
        return expected.equals(actual == null ? "" : actual);
    }

    private OptimizationExperiment copy(OptimizationExperiment current, String status, String runId,
                                        String snapshotId, String benchmarkId, String failureCode, Actor actor) {
        Instant now = clock.instant();
        return new OptimizationExperiment(current.experimentId(), current.workItemId(), current.skillId(),
                current.sourceVersion(), current.candidateVersion(), current.dataSource(), current.runtimeId(),
                current.mcpServerId(), current.llmProviderId(), current.suiteId(), current.suiteVersion(), status,
                runId, snapshotId, benchmarkId, failureCode, current.createdBy(), current.createdAt(), actor.userId(), now);
    }

    private OptimizationExperiment withDecision(OptimizationExperiment current,
                                                OptimizationExperimentDecision decision, Actor actor) {
        Instant now = clock.instant();
        return new OptimizationExperiment(current.experimentId(), current.workItemId(), current.skillId(),
                current.sourceVersion(), current.candidateVersion(), current.dataSource(), current.runtimeId(),
                current.mcpServerId(), current.llmProviderId(), current.suiteId(), current.suiteVersion(),
                current.status(), current.evaluationRunId(), current.qualitySnapshotId(), current.benchmarkId(),
                current.failureCode(), current.createdBy(), current.createdAt(), actor.userId(), now, decision);
    }

    private void ensureCandidate(String skillId, String candidateVersion) {
        boolean exists = governanceStore.snapshot().versions().stream()
                .anyMatch(version -> skillId.equals(version.skillId()) && candidateVersion.equals(version.version()));
        if (!exists) throw new OptimizationExperimentInvalidStateException("candidateVersion does not exist for Skill");
    }

    private OptimizationExperiment findInternal(String experimentId) {
        return store.find(experimentId).orElseThrow(() -> new OptimizationExperimentNotFoundException(experimentId));
    }

    private String stableFailureCode(RuntimeException failure) {
        return stableFailureCode(failure == null ? "" : failure.getMessage());
    }

    private String stableFailureCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) return normalized;
        return "EVALUATION_EXECUTION_FAILED";
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private void audit(String action, OptimizationExperiment experiment, Actor actor, String requestId,
                       Map<String, String> metadata) {
        Map<String, String> values = new HashMap<>(metadata);
        values.put("workItemId", experiment.workItemId());
        values.put("status", experiment.status());
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "OPTIMIZATION_EXPERIMENT",
                experiment.experimentId(), actor.userId(), actor.role(), requestId, experiment.updatedAt(), values));
    }

    private void requireAdmin(Actor actor) {
        RoleGuard.require(actor, Set.of("admin"));
    }
}
