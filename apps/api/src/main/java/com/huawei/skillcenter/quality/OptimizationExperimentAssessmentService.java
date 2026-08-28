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
public class OptimizationExperimentAssessmentService {
    private final OptimizationExperimentAssessmentRepository store;
    private final OptimizationExperimentRepository experimentStore;
    private final OptimizationExperimentObservationRepository observationStore;
    private final RuntimeOperationsService runtimeOperations;
    private final OptimizationSuggestionThresholdsStore thresholdsStore;
    private final GovernanceStore governanceStore;
    private final OptimizationWorkItemService workItemService;
    private final Clock clock;
    private final OptimizationExperimentAssessmentCalculator calculator;

    @Autowired
    public OptimizationExperimentAssessmentService(OptimizationExperimentAssessmentRepository store,
                                                   OptimizationExperimentRepository experimentStore,
                                                   OptimizationExperimentObservationRepository observationStore,
                                                   RuntimeOperationsService runtimeOperations,
                                                   OptimizationSuggestionThresholdsStore thresholdsStore,
                                                   GovernanceStore governanceStore,
                                                   OptimizationWorkItemService workItemService) {
        this(store, experimentStore, observationStore, runtimeOperations, thresholdsStore, governanceStore,
                workItemService, Clock.systemUTC(), new OptimizationExperimentAssessmentCalculator());
    }

    OptimizationExperimentAssessmentService(OptimizationExperimentAssessmentRepository store,
                                            OptimizationExperimentRepository experimentStore,
                                            OptimizationExperimentObservationRepository observationStore,
                                            RuntimeOperationsService runtimeOperations,
                                            OptimizationSuggestionThresholdsStore thresholdsStore,
                                            GovernanceStore governanceStore,
                                            Clock clock) {
        this(store, experimentStore, observationStore, runtimeOperations, thresholdsStore, governanceStore, clock,
                new OptimizationExperimentAssessmentCalculator());
    }

    OptimizationExperimentAssessmentService(OptimizationExperimentAssessmentRepository store,
                                            OptimizationExperimentRepository experimentStore,
                                            OptimizationExperimentObservationRepository observationStore,
                                            RuntimeOperationsService runtimeOperations,
                                            OptimizationSuggestionThresholdsStore thresholdsStore,
                                            GovernanceStore governanceStore,
                                            Clock clock,
                                            OptimizationExperimentAssessmentCalculator calculator) {
        this(store, experimentStore, observationStore, runtimeOperations, thresholdsStore, governanceStore,
                null, clock, calculator);
    }

    OptimizationExperimentAssessmentService(OptimizationExperimentAssessmentRepository store,
                                            OptimizationExperimentRepository experimentStore,
                                            OptimizationExperimentObservationRepository observationStore,
                                            RuntimeOperationsService runtimeOperations,
                                            OptimizationSuggestionThresholdsStore thresholdsStore,
                                            GovernanceStore governanceStore,
                                            OptimizationWorkItemService workItemService,
                                            Clock clock) {
        this(store, experimentStore, observationStore, runtimeOperations, thresholdsStore, governanceStore,
                workItemService, clock, new OptimizationExperimentAssessmentCalculator());
    }

    OptimizationExperimentAssessmentService(OptimizationExperimentAssessmentRepository store,
                                            OptimizationExperimentRepository experimentStore,
                                            OptimizationExperimentObservationRepository observationStore,
                                            RuntimeOperationsService runtimeOperations,
                                            OptimizationSuggestionThresholdsStore thresholdsStore,
                                            GovernanceStore governanceStore,
                                            OptimizationWorkItemService workItemService,
                                            Clock clock,
                                            OptimizationExperimentAssessmentCalculator calculator) {
        this.store = store;
        this.experimentStore = experimentStore;
        this.observationStore = observationStore;
        this.runtimeOperations = runtimeOperations;
        this.thresholdsStore = thresholdsStore;
        this.governanceStore = governanceStore;
        this.workItemService = workItemService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.calculator = calculator;
    }

    public List<OptimizationExperimentAssessment> list(String experimentId, Actor actor) {
        requireAdmin(actor);
        return store.findAll(normalizeOptional(experimentId));
    }

    public OptimizationExperimentAssessment find(String assessmentId, Actor actor) {
        requireAdmin(actor);
        return store.find(assessmentId).orElseThrow(() -> new OptimizationExperimentAssessmentNotFoundException(assessmentId));
    }

    public OptimizationExperimentAssessment assess(String experimentId,
                                                   OptimizationExperimentAssessmentRequest request,
                                                   Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) throw new IllegalArgumentException("assessment request must not be null");
        String observationId = normalizeOptional(request.observationId());
        if (observationId.isBlank()) throw new IllegalArgumentException("observationId is required");
        OptimizationExperiment experiment = experimentStore.find(experimentId)
                .orElseThrow(() -> new OptimizationExperimentNotFoundException(experimentId));
        ensureAssessable(experiment);
        OptimizationExperimentObservation observation = observationStore.find(observationId)
                .orElseThrow(() -> new OptimizationExperimentInvalidStateException("observationId was not found"));
        ensureObservationContext(experiment, observation);
        String window = OptimizationExperimentObservation.normalizeWindow(observation.window());
        RuntimeOperationsWindow parsedWindow = RuntimeOperationsWindow.parse(window);
        RuntimeOperationsSnapshot baseline = runtimeOperations.snapshot(new RuntimeOperationsQuery(parsedWindow,
                experiment.skillId(), experiment.sourceVersion(), null, experiment.dataSource(), observation.capturedAt(),
                blankToNull(experiment.runtimeId()), blankToNull(experiment.mcpServerId()), blankToNull(experiment.llmProviderId())));
        OptimizationSuggestionThresholds thresholds = thresholdsStore.get();
        OptimizationExperimentAssessmentCalculator.Result result = calculator.calculate(observation, baseline, thresholds);
        Instant now = clock.instant();
        OptimizationExperimentAssessment assessment = new OptimizationExperimentAssessment(
                "assessment-" + UUID.randomUUID(), experiment.experimentId(), experiment.workItemId(), experiment.skillId(),
                experiment.sourceVersion(), experiment.candidateVersion(), experiment.dataSource(), experiment.runtimeId(),
                experiment.mcpServerId(), experiment.llmProviderId(), experiment.suiteId(), experiment.suiteVersion(),
                window, observation.observationId(), result.candidate(),
                result.baseline(), thresholds.minSuccessRatePercent(), thresholds.maxP95Ms(), thresholds.minRuntimeSamples(),
                result.conclusion(), result.reasonCode(), result.recommendedAction(), request.action(), request.note(),
                actor.userId(), now);
        OptimizationExperimentAssessment saved = store.create(assessment);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("assessmentId", saved.assessmentId());
        metadata.put("observationId", saved.observationId());
        metadata.put("workItemId", saved.workItemId());
        metadata.put("evidenceType", "POST_RELEASE_ASSESSMENT");
        metadata.put("evidenceId", saved.assessmentId());
        metadata.put("conclusion", saved.conclusion());
        metadata.put("reasonCode", saved.reasonCode());
        metadata.put("recommendedAction", saved.recommendedAction());
        metadata.put("action", saved.action());
        metadata.put("dataSource", saved.dataSource());
        metadata.put("window", saved.window());
        metadata.put("minRuntimeSamples", String.valueOf(saved.minRuntimeSamples()));
        if (OptimizationExperimentAssessment.CREATE_FOLLOW_UP.equals(saved.action()) && workItemService != null) {
            OptimizationWorkItem followUp = workItemService.createPostReleaseFollowUp(saved, actor, requestId);
            metadata.put("followUpWorkItemId", followUp.workItemId());
        }
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), "OPTIMIZATION_EXPERIMENT_ASSESSED",
                "OPTIMIZATION_EXPERIMENT", saved.experimentId(), actor.userId(), actor.role(), requestId, saved.assessedAt(), metadata));
        return saved;
    }

    private void ensureAssessable(OptimizationExperiment experiment) {
        if (!OptimizationExperimentStatus.COMPLETED.equals(experiment.status())
                || experiment.decision() == null
                || !OptimizationExperimentDecision.PROMOTE_CANDIDATE.equals(experiment.decision().decision())) {
            throw new OptimizationExperimentInvalidStateException(
                    "post-release assessment requires a completed PROMOTE_CANDIDATE experiment");
        }
        boolean wasPublished = governanceStore.snapshot().versions().stream()
                .anyMatch(version -> experiment.skillId().equals(version.skillId())
                        && experiment.candidateVersion().equals(version.version())
                        && version.publishedAt() != null);
        if (!wasPublished) throw new OptimizationExperimentInvalidStateException("candidateVersion must be published before assessment");
    }

    private void ensureObservationContext(OptimizationExperiment experiment,
                                          OptimizationExperimentObservation observation) {
        if (!experiment.experimentId().equals(observation.experimentId())
                || !experiment.skillId().equals(observation.skillId())
                || !experiment.candidateVersion().equals(observation.candidateVersion())
                || !experiment.dataSource().equals(observation.dataSource())
                || !experiment.runtimeId().equals(observation.runtimeId())
                || !experiment.mcpServerId().equals(observation.mcpServerId())
                || !experiment.llmProviderId().equals(observation.llmProviderId())) {
            throw new OptimizationExperimentInvalidStateException("observation context does not match experiment context");
        }
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
