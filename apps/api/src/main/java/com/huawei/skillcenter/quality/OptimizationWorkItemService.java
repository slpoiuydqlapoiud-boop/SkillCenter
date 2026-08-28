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
public class OptimizationWorkItemService {
    private final OptimizationWorkItemRepository store;
    private final OptimizationSuggestionService suggestionService;
    private final QualityEvaluationService evaluationService;
    private final BenchmarkService benchmarkService;
    private final GovernanceStore governanceStore;
    private final OptimizationExperimentAssessmentRepository assessmentStore;
    private final Clock clock;

    public OptimizationWorkItemService(OptimizationWorkItemRepository store,
                                       OptimizationSuggestionService suggestionService,
                                         QualityEvaluationService evaluationService,
                                         BenchmarkService benchmarkService,
                                         GovernanceStore governanceStore) {
        this(store, suggestionService, evaluationService, benchmarkService, governanceStore, null, Clock.systemUTC());
    }

    @Autowired
    public OptimizationWorkItemService(OptimizationWorkItemRepository store,
                                       OptimizationSuggestionService suggestionService,
                                       QualityEvaluationService evaluationService,
                                       BenchmarkService benchmarkService,
                                       GovernanceStore governanceStore,
                                       OptimizationExperimentAssessmentRepository assessmentStore) {
        this(store, suggestionService, evaluationService, benchmarkService, governanceStore, assessmentStore,
                Clock.systemUTC());
    }

    OptimizationWorkItemService(OptimizationWorkItemRepository store,
                                OptimizationSuggestionService suggestionService,
                                QualityEvaluationService evaluationService,
                                BenchmarkService benchmarkService,
                                GovernanceStore governanceStore,
                                Clock clock) {
        this(store, suggestionService, evaluationService, benchmarkService, governanceStore, null, clock);
    }

    OptimizationWorkItemService(OptimizationWorkItemRepository store,
                                OptimizationSuggestionService suggestionService,
                                QualityEvaluationService evaluationService,
                                BenchmarkService benchmarkService,
                                GovernanceStore governanceStore,
                                OptimizationExperimentAssessmentRepository assessmentStore,
                                Clock clock) {
        this.store = store;
        this.suggestionService = suggestionService;
        this.evaluationService = evaluationService;
        this.benchmarkService = benchmarkService;
        this.governanceStore = governanceStore;
        this.assessmentStore = assessmentStore;
        this.clock = clock;
    }

    public OptimizationWorkItem create(OptimizationWorkItemCreateRequest request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) throw new IllegalArgumentException("work item request must not be null");
        String dataSource = normalizeDataSource(request.dataSource());
        String suiteId = normalizeSuiteIdentifier(request.suiteId(), "suiteId");
        String suiteVersion = normalizeSuiteIdentifier(request.suiteVersion(), "suiteVersion");
        if (suiteId.isBlank() != suiteVersion.isBlank()) {
            throw new IllegalArgumentException("suiteId and suiteVersion must be provided together");
        }
        List<OptimizationSuggestion> candidates = suggestionService.suggestions(
                request.skillId(), request.sourceVersion(), RuntimeOperationsWindow.TWENTY_FOUR_HOURS,
                dataSource, normalizeOptional(request.runtimeId()), normalizeOptional(request.mcpServerId()),
                normalizeOptional(request.llmProviderId()));
        OptimizationSuggestion suggestion = candidates.stream()
                .filter(item -> item.id().equals(request.suggestionId()))
                .findFirst()
                .orElseThrow(() -> new OptimizationWorkItemEvidenceException("optimization suggestion was not found for the requested context"));
        Instant now = Instant.now(clock);
        String ownerId = request.ownerId() == null || request.ownerId().isBlank() ? actor.userId() : request.ownerId().trim();
        OptimizationWorkItem item = new OptimizationWorkItem(
                "work-" + UUID.randomUUID(), request.skillId(), request.sourceVersion(), suggestion.id(),
                suggestion.title(), suggestion.category(), suggestion.severity(), suggestion.evidence(),
                request.hypothesis(), ownerId, OptimizationWorkItemStatus.OPEN, "", OptimizationWorkItem.NONE, "", "",
                dataSource, normalizeOptional(request.runtimeId()), normalizeOptional(request.mcpServerId()),
                normalizeOptional(request.llmProviderId()), suiteId, suiteVersion, actor.userId(), now, actor.userId(), now);
        OptimizationWorkItem created;
        try {
            created = store.create(item);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("active work item")) {
                throw new OptimizationWorkItemConflictException(exception.getMessage());
            }
            throw exception;
        }
        Map<String, String> metadata = new HashMap<>(Map.of(
                "suggestionId", created.suggestionId(), "sourceVersion", created.sourceVersion()));
        if (!created.suiteId().isBlank()) {
            metadata.put("suiteId", created.suiteId());
            metadata.put("suiteVersion", created.suiteVersion());
        }
        audit("OPTIMIZATION_WORK_ITEM_CREATED", created, actor, requestId, metadata);
        return created;
    }

    /** Creates the next optimization item from an explicit post-release assessment action. */
    public OptimizationWorkItem createPostReleaseFollowUp(OptimizationExperimentAssessment assessment,
                                                           Actor actor, String requestId) {
        requireAdmin(actor);
        if (assessment == null) throw new IllegalArgumentException("assessment must not be null");
        String suggestionId = "post-release-assessment:" + assessment.assessmentId();
        Instant now = Instant.now(clock);
        String severity = OptimizationExperimentAssessment.REGRESSION.equals(assessment.conclusion())
                ? "HIGH" : "MEDIUM";
        OptimizationWorkItem candidate = new OptimizationWorkItem(
                "work-follow-up-" + assessment.assessmentId(), assessment.skillId(), assessment.candidateVersion(),
                suggestionId, "发布后评估后续优化 · " + assessment.candidateVersion(),
                "POST_RELEASE_ASSESSMENT", severity,
                List.of("postReleaseAssessmentId=" + assessment.assessmentId(),
                        "observationId=" + assessment.observationId(),
                        "conclusion=" + assessment.conclusion(), "reasonCode=" + assessment.reasonCode()),
                "基于发布后评估 " + assessment.reasonCode() + "，验证候选版本的后续改进方案",
                actor.userId(), OptimizationWorkItemStatus.OPEN, assessment.candidateVersion(),
                OptimizationWorkItem.NONE, "", "", assessment.dataSource(), assessment.runtimeId(),
                assessment.mcpServerId(), assessment.llmProviderId(), assessment.suiteId(), assessment.suiteVersion(),
                actor.userId(), now, actor.userId(), now);
        OptimizationWorkItem created;
        try {
            created = store.create(candidate);
        } catch (IllegalArgumentException conflict) {
            if (!isIdempotentFollowUpConflict(conflict)) throw conflict;
            return store.findAll(assessment.skillId(), "", "", assessment.candidateVersion()).stream()
                    .filter(item -> suggestionId.equals(item.suggestionId()))
                    .findFirst()
                    .orElseThrow(() -> conflict);
        }
        audit("OPTIMIZATION_FOLLOW_UP_CREATED", created, actor, requestId,
                Map.of("assessmentId", assessment.assessmentId(), "observationId", assessment.observationId(),
                        "conclusion", assessment.conclusion(), "reasonCode", assessment.reasonCode()));
        return created;
    }

    private boolean isIdempotentFollowUpConflict(IllegalArgumentException exception) {
        String message = exception.getMessage();
        return "workItemId already exists".equals(message) || "active work item already exists".equals(message);
    }

    public List<OptimizationWorkItem> list(String skillId, String status, String ownerId, String sourceVersion, Actor actor) {
        requireAdmin(actor);
        return store.findAll(normalizeOptional(skillId), normalizeOptional(status), normalizeOptional(ownerId), normalizeOptional(sourceVersion));
    }

    public OptimizationWorkItem find(String workItemId, Actor actor) {
        requireAdmin(actor);
        return store.find(workItemId).orElseThrow(() -> new OptimizationWorkItemNotFoundException(workItemId));
    }

    public OptimizationWorkItem transition(String workItemId, OptimizationWorkItemStatusRequest request,
                                           Actor actor, String requestId) {
        requireAdmin(actor);
        OptimizationWorkItem current = findInternal(workItemId);
        if (request == null) throw new IllegalArgumentException("status request must not be null");
        String target = OptimizationWorkItemStatus.normalize(request.status());
        if (!allowed(current.status(), target)) {
            throw new OptimizationWorkItemInvalidStateException("work item cannot transition from "
                    + current.status() + " to " + target);
        }
        String candidateVersion = normalizeOptional(request.candidateVersion());
        if (candidateVersion.isBlank()) candidateVersion = current.candidateVersion();
        if (Set.of(OptimizationWorkItemStatus.READY_FOR_EVALUATION, OptimizationWorkItemStatus.COMPLETED).contains(target)) {
            ensureCandidate(current.skillId(), candidateVersion);
        }
        String evidenceType = current.evidenceType();
        String evidenceId = current.evidenceId();
        String outcome = normalizeOptional(request.outcome());
        if (outcome.isBlank()) outcome = current.outcome();
        if (OptimizationWorkItemStatus.COMPLETED.equals(target)) {
            if (OptimizationWorkItem.NONE.equals(evidenceType) || evidenceId.isBlank()) {
                throw new OptimizationWorkItemInvalidStateException("completed work item requires matching evidence");
            }
            if (outcome.isBlank()) throw new OptimizationWorkItemInvalidStateException("completed work item requires outcome");
        }
        if (OptimizationWorkItemStatus.ABANDONED.equals(target) && outcome.isBlank()) {
            throw new OptimizationWorkItemInvalidStateException("abandoned work item requires outcome");
        }
        if (OptimizationWorkItemStatus.OPEN.equals(target)) {
            evidenceType = OptimizationWorkItem.NONE;
            evidenceId = "";
            outcome = "";
        }
        Instant now = Instant.now(clock);
        OptimizationWorkItem updated = copy(current, target, candidateVersion, evidenceType, evidenceId,
                outcome, actor, now);
        store.replace(updated);
        audit("OPTIMIZATION_WORK_ITEM_STATUS_CHANGED", updated, actor, requestId,
                Map.of("fromStatus", current.status(), "toStatus", target));
        return updated;
    }

    public OptimizationWorkItem bindEvidence(String workItemId, OptimizationWorkItemEvidenceRequest request,
                                              Actor actor, String requestId) {
        requireAdmin(actor);
        OptimizationWorkItem current = findInternal(workItemId);
        if (!Set.of(OptimizationWorkItemStatus.READY_FOR_EVALUATION, OptimizationWorkItemStatus.IN_PROGRESS)
                .contains(current.status())) {
            throw new OptimizationWorkItemInvalidStateException("evidence can only be bound while work item is being evaluated");
        }
        if (request == null) throw new IllegalArgumentException("evidence request must not be null");
        String type = normalizeEvidenceType(request.evidenceType());
        String id = request.evidenceId() == null ? "" : request.evidenceId().trim();
        if (OptimizationWorkItem.NONE.equals(type)) {
            id = "";
        } else {
            if (id.isBlank()) throw new OptimizationWorkItemEvidenceException("evidenceId is required");
            ensureEvidenceMatches(current, type, id);
        }
        String outcome = request.outcome() == null || request.outcome().isBlank() ? current.outcome() : request.outcome().trim();
        Instant now = Instant.now(clock);
        OptimizationWorkItem updated = copy(current, current.status(), current.candidateVersion(), type, id,
                outcome, actor, now);
        store.replace(updated);
        audit("OPTIMIZATION_WORK_ITEM_EVIDENCE_BOUND", updated, actor, requestId,
                Map.of("evidenceType", type, "evidenceId", id));
        return updated;
    }

    private void ensureEvidenceMatches(OptimizationWorkItem item, String type, String evidenceId) {
        switch (type) {
            case OptimizationWorkItem.EVALUATION_RUN -> {
                EvaluationRun run;
                try {
                    run = evaluationService.find(evidenceId);
                } catch (RuntimeException exception) {
                    throw new OptimizationWorkItemEvidenceException("evaluation evidence was not found");
                }
                if (run.status() != EvaluationRunStatus.COMPLETED || !sameContext(item, run.skillId(), run.skillVersion(),
                        run.dataSource(), run.runtimeId(), run.mcpServerId(), run.llmProviderId(),
                        run.suiteId(), run.suiteVersion())) {
                    throw new OptimizationWorkItemEvidenceException("evaluation evidence does not match candidate context");
                }
            }
            case OptimizationWorkItem.QUALITY_SNAPSHOT -> {
                QualitySnapshot snapshot = evaluationService.findSnapshot(evidenceId);
                if (snapshot == null || !sameContext(item, snapshot.skillId(), snapshot.skillVersion(), snapshot.dataSource(),
                        snapshot.runtimeId(), snapshot.mcpServerId(), snapshot.llmProviderId(),
                        snapshot.suiteId(), snapshot.suiteVersion())) {
                    throw new OptimizationWorkItemEvidenceException("quality snapshot does not match candidate context");
                }
            }
            case OptimizationWorkItem.BENCHMARK -> {
                List<BenchmarkResult> results = item.suiteId().isBlank()
                        ? benchmarkService.list(item.skillId(), item.dataSource(), item.runtimeId(),
                        item.mcpServerId(), item.llmProviderId())
                        : benchmarkService.list(item.skillId(), item.dataSource(), item.runtimeId(),
                        item.mcpServerId(), item.llmProviderId(), item.suiteId(), item.suiteVersion());
                boolean matches = results.stream()
                        .anyMatch(result -> result.benchmarkId().equals(evidenceId)
                                && result.candidateVersion().equals(item.candidateVersion())
                                && sameContext(item, result.skillId(), result.candidateVersion(), result.dataSource(),
                                result.runtimeId(), result.mcpServerId(), result.llmProviderId(),
                                result.suiteId(), result.suiteVersion()));
                if (!matches) throw new OptimizationWorkItemEvidenceException("benchmark evidence does not match candidate context");
            }
            case OptimizationWorkItem.POST_RELEASE_ASSESSMENT -> {
                if (assessmentStore == null) {
                    throw new OptimizationWorkItemEvidenceException("post-release assessment evidence is unavailable");
                }
                OptimizationExperimentAssessment assessment = assessmentStore.find(evidenceId).orElse(null);
                if (assessment == null || !sameAssessmentContext(item, assessment)) {
                    throw new OptimizationWorkItemEvidenceException("post-release assessment does not match candidate context");
                }
            }
            default -> throw new OptimizationWorkItemEvidenceException("unsupported evidence type");
        }
    }

    private boolean sameAssessmentContext(OptimizationWorkItem item, OptimizationExperimentAssessment assessment) {
        return item.skillId().equals(assessment.skillId())
                && item.candidateVersion().equals(assessment.candidateVersion())
                && ("all".equals(item.dataSource()) || item.dataSource().equals(assessment.dataSource()))
                && item.runtimeId().equals(assessment.runtimeId())
                && item.mcpServerId().equals(assessment.mcpServerId())
                && item.llmProviderId().equals(assessment.llmProviderId())
                && item.suiteId().equals(assessment.suiteId())
                && item.suiteVersion().equals(assessment.suiteVersion());
    }

    private boolean sameContext(OptimizationWorkItem item, String skillId, String version, String dataSource,
                                String runtimeId, String mcpServerId, String llmProviderId,
                                String suiteId, String suiteVersion) {
        return item.skillId().equals(skillId) && item.candidateVersion().equals(version)
                && ("all".equals(item.dataSource()) || item.dataSource().equals(dataSource))
                && item.runtimeId().equals(normalizeOptional(runtimeId))
                && item.mcpServerId().equals(normalizeOptional(mcpServerId))
                && item.llmProviderId().equals(normalizeOptional(llmProviderId))
                && suiteMatches(item.suiteId(), item.suiteVersion(), suiteId, suiteVersion);
    }

    private boolean suiteMatches(String itemSuiteId, String itemSuiteVersion,
                                 String evidenceSuiteId, String evidenceSuiteVersion) {
        String normalizedEvidenceSuiteId = normalizeOptional(evidenceSuiteId);
        String normalizedEvidenceSuiteVersion = normalizeOptional(evidenceSuiteVersion);
        boolean itemPinned = !itemSuiteId.isBlank() || !itemSuiteVersion.isBlank();
        boolean evidencePinned = !normalizedEvidenceSuiteId.isBlank() || !normalizedEvidenceSuiteVersion.isBlank();
        if (itemPinned && (itemSuiteId.isBlank() || itemSuiteVersion.isBlank())) return false;
        if (evidencePinned && (normalizedEvidenceSuiteId.isBlank() || normalizedEvidenceSuiteVersion.isBlank())) {
            return false;
        }
        if (!itemPinned) return true;
        return itemSuiteId.equals(normalizedEvidenceSuiteId) && itemSuiteVersion.equals(normalizedEvidenceSuiteVersion);
    }

    private void ensureCandidate(String skillId, String candidateVersion) {
        if (candidateVersion == null || candidateVersion.isBlank()) {
            throw new OptimizationWorkItemInvalidStateException("candidateVersion is required before evaluation");
        }
        boolean exists = governanceStore.snapshot().versions().stream()
                .anyMatch(version -> skillId.equals(version.skillId()) && candidateVersion.equals(version.version()));
        if (!exists) throw new OptimizationWorkItemInvalidStateException("candidateVersion does not exist for Skill");
    }

    private OptimizationWorkItem copy(OptimizationWorkItem current, String status, String candidateVersion,
                                      String evidenceType, String evidenceId, String outcome,
                                      Actor actor, Instant now) {
        return new OptimizationWorkItem(current.workItemId(), current.skillId(), current.sourceVersion(), current.suggestionId(),
                current.suggestionTitle(), current.suggestionCategory(), current.suggestionSeverity(), current.suggestionEvidence(),
                current.hypothesis(), current.ownerId(), status, candidateVersion, evidenceType, evidenceId, outcome,
                current.dataSource(), current.runtimeId(), current.mcpServerId(), current.llmProviderId(),
                current.suiteId(), current.suiteVersion(), current.createdBy(), current.createdAt(), actor.userId(), now);
    }

    private OptimizationWorkItem findInternal(String workItemId) {
        return store.find(workItemId).orElseThrow(() -> new OptimizationWorkItemNotFoundException(workItemId));
    }

    private boolean allowed(String from, String to) {
        return switch (OptimizationWorkItemStatus.normalize(from)) {
            case OptimizationWorkItemStatus.OPEN -> Set.of(OptimizationWorkItemStatus.PLANNED, OptimizationWorkItemStatus.ABANDONED).contains(to);
            case OptimizationWorkItemStatus.PLANNED -> Set.of(OptimizationWorkItemStatus.IN_PROGRESS, OptimizationWorkItemStatus.ABANDONED).contains(to);
            case OptimizationWorkItemStatus.IN_PROGRESS -> Set.of(OptimizationWorkItemStatus.READY_FOR_EVALUATION, OptimizationWorkItemStatus.ABANDONED).contains(to);
            case OptimizationWorkItemStatus.READY_FOR_EVALUATION -> Set.of(OptimizationWorkItemStatus.COMPLETED, OptimizationWorkItemStatus.ABANDONED).contains(to);
            case OptimizationWorkItemStatus.ABANDONED -> OptimizationWorkItemStatus.OPEN.equals(to);
            case OptimizationWorkItemStatus.COMPLETED -> false;
            default -> false;
        };
    }

    private void audit(String action, OptimizationWorkItem item, Actor actor, String requestId, Map<String, String> metadata) {
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "OPTIMIZATION_WORK_ITEM",
                item.workItemId(), actor.userId(), actor.role(), requestId, item.updatedAt(), metadata));
    }

    private void requireAdmin(Actor actor) {
        RoleGuard.require(actor, Set.of("admin"));
    }

    private String normalizeDataSource(String value) {
        String normalized = value == null || value.isBlank() ? "all" : value.trim().toLowerCase();
        if (!Set.of("mock", "production", "all").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private String normalizeEvidenceType(String value) {
        String normalized = value == null || value.isBlank() ? OptimizationWorkItem.NONE : value.trim().toUpperCase();
        if (!Set.of(OptimizationWorkItem.NONE, OptimizationWorkItem.EVALUATION_RUN,
                OptimizationWorkItem.QUALITY_SNAPSHOT, OptimizationWorkItem.BENCHMARK,
                OptimizationWorkItem.POST_RELEASE_ASSESSMENT).contains(normalized)) {
            throw new OptimizationWorkItemEvidenceException("unsupported evidence type");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeSuiteIdentifier(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized.length() > 128 || (!normalized.isBlank()
                && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
