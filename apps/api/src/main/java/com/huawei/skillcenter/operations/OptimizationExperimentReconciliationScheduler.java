package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import com.huawei.skillcenter.quality.OptimizationExperiment;
import com.huawei.skillcenter.quality.OptimizationExperimentBenchmarkRequest;
import com.huawei.skillcenter.quality.OptimizationExperimentRepository;
import com.huawei.skillcenter.quality.OptimizationExperimentService;
import com.huawei.skillcenter.quality.OptimizationExperimentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resumes queued/running optimization experiments without changing their manual decision boundary.
 * It is opt-in and only runs when all experiment evidence is backed by shared PostgreSQL state.
 */
@Component
@ConditionalOnProperty(name = "skill-center.optimization-experiments.scheduler-enabled", havingValue = "true")
public class OptimizationExperimentReconciliationScheduler {
    private static final Actor SCHEDULER_ACTOR = new Actor("optimization-scheduler", "admin");
    private final OptimizationExperimentService service;
    private final OptimizationExperimentRepository experiments;
    private final boolean autoBenchmarkEnabled;
    private final boolean autoDecisionEnabled;

    @Autowired
    public OptimizationExperimentReconciliationScheduler(
            OptimizationExperimentService service,
            OptimizationExperimentRepository experiments,
            OptimizationExperimentBackendHealth experimentBackend,
            OptimizationWorkItemBackendHealth workItemBackend,
            PersistenceControlProperties properties,
            PersistenceBackend persistence,
            BenchmarkBackendHealth benchmarkBackend,
            @Value("${skill-center.optimization-experiments.auto-benchmark-enabled:false}") boolean autoBenchmarkEnabled,
            @Value("${skill-center.optimization-experiments.auto-decision-enabled:false}") boolean autoDecisionEnabled) {
        this.service = require(service, "service");
        this.experiments = require(experiments, "experiments");
        require(experimentBackend, "experimentBackend");
        require(workItemBackend, "workItemBackend");
        require(properties, "properties");
        require(persistence, "persistence");
        requireReadyExperimentBackend(experimentBackend);
        requireReadyWorkItemBackend(workItemBackend);
        requireReadyQualityEvidenceBackend(properties, persistence);
        if (autoBenchmarkEnabled || autoDecisionEnabled) {
            requireReadyBenchmarkBackend(benchmarkBackend);
        }
        this.autoBenchmarkEnabled = autoBenchmarkEnabled;
        this.autoDecisionEnabled = autoDecisionEnabled;
    }

    public OptimizationExperimentReconciliationScheduler(
            OptimizationExperimentService service,
            OptimizationExperimentRepository experiments,
            OptimizationExperimentBackendHealth experimentBackend,
            OptimizationWorkItemBackendHealth workItemBackend,
            PersistenceControlProperties properties,
            PersistenceBackend persistence) {
        this(service, experiments, experimentBackend, workItemBackend, properties, persistence, null, false, false);
    }

    @Scheduled(
            fixedDelayString = "${skill-center.optimization-experiments.reconciliation-interval-ms:60000}",
            initialDelayString = "${skill-center.optimization-experiments.reconciliation-initial-delay-ms:60000}")
    public void reconcileScheduled() {
        runOnce();
    }

    public void runOnce() {
        try {
            for (OptimizationExperiment experiment : experiments.findAll("", "", "")) {
                if (experiment == null || !shouldProcess(experiment)) continue;
                try {
                    OptimizationExperiment reconciled = service.reconcile(experiment.experimentId(), SCHEDULER_ACTOR,
                            requestId(experiment.experimentId()));
                    if (reconciled == null || !OptimizationExperimentStatus.COMPLETED.equals(reconciled.status())) {
                        continue;
                    }
                    if (autoBenchmarkEnabled && reconciled.benchmarkId().isBlank()) {
                        try {
                            reconciled = service.benchmark(reconciled.experimentId(),
                                    new OptimizationExperimentBenchmarkRequest("24h"), SCHEDULER_ACTOR,
                                    requestId(reconciled.experimentId()) + "-benchmark");
                        } catch (RuntimeException ignored) {
                            // Missing baseline or incomplete evidence remains retryable on the next cycle.
                            continue;
                        }
                    }
                    if (autoDecisionEnabled && reconciled != null
                            && OptimizationExperimentStatus.COMPLETED.equals(reconciled.status())
                            && !reconciled.benchmarkId().isBlank() && reconciled.decision() == null) {
                        try {
                            service.decide(reconciled.experimentId(), SCHEDULER_ACTOR,
                                    requestId(reconciled.experimentId()) + "-decision");
                        } catch (RuntimeException ignored) {
                            // Missing baseline or incomplete evidence remains retryable on the next cycle.
                        }
                    }
                } catch (RuntimeException ignored) {
                    // One provider/evidence failure must not prevent other experiments from progressing.
                }
            }
        } catch (RuntimeException ignored) {
            // A persistence failure must not terminate the scheduled task.
        }
    }

    private String requestId(String experimentId) {
        return "scheduler-optimization-experiment-" + experimentId;
    }

    private boolean shouldProcess(OptimizationExperiment experiment) {
        if (!OptimizationExperimentStatus.isTerminal(experiment.status())) return true;
        if (!OptimizationExperimentStatus.COMPLETED.equals(experiment.status())) return false;
        return (autoBenchmarkEnabled && experiment.benchmarkId().isBlank())
                || (autoDecisionEnabled && !experiment.benchmarkId().isBlank() && experiment.decision() == null);
    }

    private void requireReadyExperimentBackend(OptimizationExperimentBackendHealth backend) {
        OptimizationExperimentBackendReadiness readiness = backend.readiness();
        if (readiness == null || !"READY".equals(readiness.status())
                || !"postgresql".equalsIgnoreCase(readiness.backend())) {
            throw new IllegalStateException("optimization experiment scheduler requires READY shared experiment backend");
        }
    }

    private void requireReadyWorkItemBackend(OptimizationWorkItemBackendHealth backend) {
        OptimizationWorkItemBackendReadiness readiness = backend.readiness();
        if (readiness == null || !"READY".equals(readiness.status())
                || !"postgresql".equalsIgnoreCase(readiness.backend())) {
            throw new IllegalStateException("optimization experiment scheduler requires READY shared work-item backend");
        }
    }

    private void requireReadyQualityEvidenceBackend(PersistenceControlProperties properties,
                                                    PersistenceBackend persistence) {
        if (!"postgresql".equalsIgnoreCase(properties.normalizedQualityEvidenceBackend())
                || !"postgresql".equalsIgnoreCase(properties.normalizedBackend())) {
            throw new IllegalStateException("optimization experiment scheduler requires PostgreSQL quality evidence");
        }
        PersistenceBackendStatus status = persistence.status();
        if (status == null || !"READY".equals(status.state())
                || !"postgresql".equalsIgnoreCase(status.backendId())
                || !hasSchema(status.schemaVersion(), 1)) {
            throw new IllegalStateException("optimization experiment scheduler requires READY quality evidence schema");
        }
    }

    private void requireReadyBenchmarkBackend(BenchmarkBackendHealth backend) {
        if (backend == null) {
            throw new IllegalStateException("optimization experiment scheduler requires READY shared benchmark backend");
        }
        BenchmarkBackendReadiness readiness = backend.readiness();
        if (readiness == null || !"READY".equals(readiness.status())
                || !"postgresql".equalsIgnoreCase(readiness.backend())) {
            throw new IllegalStateException("optimization experiment scheduler requires READY shared benchmark backend");
        }
    }

    private boolean hasSchema(String schemaVersion, int required) {
        if (schemaVersion == null || schemaVersion.isBlank()) return false;
        try {
            return Integer.parseInt(schemaVersion.trim().split("\\.", 2)[0]) >= required;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
