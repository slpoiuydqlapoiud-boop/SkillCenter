package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.operations.RuntimeOperationsQuery;
import com.huawei.skillcenter.operations.RuntimeOperationsService;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
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
public class OptimizationExperimentObservationService {
    private final OptimizationExperimentObservationRepository store;
    private final OptimizationExperimentRepository experimentStore;
    private final RuntimeOperationsService runtimeOperations;
    private final GovernanceStore governanceStore;
    private final Clock clock;

    @Autowired
    public OptimizationExperimentObservationService(OptimizationExperimentObservationRepository store,
                                                    OptimizationExperimentRepository experimentStore,
                                                    RuntimeOperationsService runtimeOperations,
                                                    GovernanceStore governanceStore) {
        this(store, experimentStore, runtimeOperations, governanceStore, Clock.systemUTC());
    }

    OptimizationExperimentObservationService(OptimizationExperimentObservationRepository store,
                                             OptimizationExperimentRepository experimentStore,
                                             RuntimeOperationsService runtimeOperations,
                                             GovernanceStore governanceStore,
                                             Clock clock) {
        this.store = store;
        this.experimentStore = experimentStore;
        this.runtimeOperations = runtimeOperations;
        this.governanceStore = governanceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<OptimizationExperimentObservation> list(String experimentId, Actor actor) {
        requireAdmin(actor);
        return store.findAll(normalizeOptional(experimentId));
    }

    public OptimizationExperimentObservation capture(String experimentId,
                                                      OptimizationExperimentObservationRequest request,
                                                      Actor actor, String requestId) {
        requireAdmin(actor);
        OptimizationExperiment experiment = experimentStore.find(experimentId)
                .orElseThrow(() -> new OptimizationExperimentNotFoundException(experimentId));
        if (!OptimizationExperimentStatus.COMPLETED.equals(experiment.status())
                || experiment.decision() == null
                || !OptimizationExperimentDecision.PROMOTE_CANDIDATE.equals(experiment.decision().decision())) {
            throw new OptimizationExperimentInvalidStateException(
                    "post-release observation requires a completed PROMOTE_CANDIDATE experiment");
        }
        boolean wasPublished = governanceStore.snapshot().versions().stream()
                .anyMatch(version -> experiment.skillId().equals(version.skillId())
                        && experiment.candidateVersion().equals(version.version())
                        && version.publishedAt() != null);
        if (!wasPublished) {
            throw new OptimizationExperimentInvalidStateException("candidateVersion must be published before observation");
        }
        String window = OptimizationExperimentObservation.normalizeWindow(request == null ? null : request.window());
        RuntimeOperationsWindow parsedWindow = RuntimeOperationsWindow.parse(window);
        RuntimeOperationsSnapshot snapshot = runtimeOperations.snapshot(new RuntimeOperationsQuery(parsedWindow,
                experiment.skillId(), experiment.candidateVersion(), null, experiment.dataSource(), clock.instant(),
                blankToNull(experiment.runtimeId()), blankToNull(experiment.mcpServerId()), blankToNull(experiment.llmProviderId())));
        RuntimeOperationsSnapshot.Totals totals = snapshot.totals();
        OptimizationExperimentObservation observation = new OptimizationExperimentObservation(
                "observation-" + UUID.randomUUID(), experiment.experimentId(), experiment.skillId(),
                experiment.candidateVersion(), experiment.dataSource(), experiment.runtimeId(),
                experiment.mcpServerId(), experiment.llmProviderId(), window, snapshot.generatedAt(), actor.userId(),
                totals.total(), totals.successes(), totals.failures(), totals.timeouts(), totals.cancellations(),
                totals.successRate(), snapshot.latency().p95Ms(), totals.total() == 0 ? "NO_TRAFFIC" : "CAPTURED");
        OptimizationExperimentObservation saved = store.create(observation);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("experimentId", saved.experimentId());
        metadata.put("evidenceType", "RUNTIME_OPERATIONS");
        metadata.put("evidenceId", saved.observationId());
        metadata.put("dataSource", saved.dataSource());
        metadata.put("window", saved.window());
        metadata.put("status", saved.observationStatus());
        putIfPresent(metadata, "runtimeId", saved.runtimeId());
        putIfPresent(metadata, "mcpServerId", saved.mcpServerId());
        putIfPresent(metadata, "llmProviderId", saved.llmProviderId());
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(),
                "OPTIMIZATION_EXPERIMENT_POST_RELEASE_OBSERVED", "OPTIMIZATION_EXPERIMENT",
                saved.experimentId(), actor.userId(), actor.role(), requestId, saved.capturedAt(), metadata));
        return saved;
    }

    private void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) values.put(key, value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private void requireAdmin(Actor actor) {
        RoleGuard.require(actor, Set.of("admin"));
    }
}
