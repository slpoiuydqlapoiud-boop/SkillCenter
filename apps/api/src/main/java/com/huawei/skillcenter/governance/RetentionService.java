package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.InvocationEventStore;
import com.huawei.skillcenter.operations.RuntimeSummaryRepository;
import com.huawei.skillcenter.operations.RuntimeSummaryStore;
import com.huawei.skillcenter.quality.QualityEvidenceRepository;
import com.huawei.skillcenter.quality.QualityEvidenceStore;
import com.huawei.skillcenter.quality.BenchmarkRepository;
import com.huawei.skillcenter.quality.SkillExecutionStore;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentRepository;
import com.huawei.skillcenter.quality.OptimizationExperimentObservationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RetentionService {
    private static final int PREVIEW_TTL_MINUTES = 10;
    private final GovernanceStore store;
    private final InvocationEventStore invocationEventStore;
    private final RuntimeSummaryRepository runtimeSummaryStore;
    private final QualityEvidenceRepository qualityEvidenceRepository;
    private final BenchmarkRepository benchmarkStore;
    private final SkillExecutionStore skillExecutionStore;
    private final OptimizationExperimentObservationRepository optimizationExperimentObservationStore;
    private final OptimizationExperimentAssessmentRepository optimizationExperimentAssessmentStore;
    private final RetentionEvidenceReferenceIndex retentionEvidenceReferenceIndex;
    private final Map<String, RetentionPreview> previews = new ConcurrentHashMap<>();
    private final Map<String, RetentionProtectionSnapshot> protectionSnapshots = new ConcurrentHashMap<>();
    private final Map<String, RetentionExecutionResult> executions = new ConcurrentHashMap<>();

    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore) {
        this(store, invocationEventStore, new RuntimeSummaryStore(), new QualityEvidenceStore(), null,
                new SkillExecutionStore(), null, null);
    }

    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore,
                            RuntimeSummaryRepository runtimeSummaryStore) {
        this(store, invocationEventStore, runtimeSummaryStore, new QualityEvidenceStore(), null,
                new SkillExecutionStore(), null, null);
    }

    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore,
                            RuntimeSummaryRepository runtimeSummaryStore,
                            QualityEvidenceRepository qualityEvidenceRepository) {
        this(store, invocationEventStore, runtimeSummaryStore, qualityEvidenceRepository, null,
                new SkillExecutionStore(), null, null);
    }

    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore,
                            RuntimeSummaryRepository runtimeSummaryStore,
                            QualityEvidenceRepository qualityEvidenceRepository,
                            BenchmarkRepository benchmarkStore) {
        this(store, invocationEventStore, runtimeSummaryStore, qualityEvidenceRepository, benchmarkStore,
                new SkillExecutionStore(), null, null);
    }

    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore,
                            RuntimeSummaryRepository runtimeSummaryStore,
                            QualityEvidenceRepository qualityEvidenceRepository,
                            BenchmarkRepository benchmarkStore,
                            SkillExecutionStore skillExecutionStore) {
        this(store, invocationEventStore, runtimeSummaryStore, qualityEvidenceRepository, benchmarkStore,
                skillExecutionStore, null, null);
    }

    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore,
                            RuntimeSummaryRepository runtimeSummaryStore,
                            QualityEvidenceRepository qualityEvidenceRepository,
                            BenchmarkRepository benchmarkStore,
                            SkillExecutionStore skillExecutionStore,
                            OptimizationExperimentObservationRepository optimizationExperimentObservationStore,
                            OptimizationExperimentAssessmentRepository optimizationExperimentAssessmentStore) {
        this(store, invocationEventStore, runtimeSummaryStore, qualityEvidenceRepository, benchmarkStore,
                skillExecutionStore, optimizationExperimentObservationStore, optimizationExperimentAssessmentStore,
                RetentionEvidenceReferenceIndex.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore,
                            RuntimeSummaryRepository runtimeSummaryStore,
                            QualityEvidenceRepository qualityEvidenceRepository,
                            BenchmarkRepository benchmarkStore,
                            SkillExecutionStore skillExecutionStore,
                            OptimizationExperimentObservationRepository optimizationExperimentObservationStore,
                            OptimizationExperimentAssessmentRepository optimizationExperimentAssessmentStore,
                            RetentionEvidenceReferenceIndex retentionEvidenceReferenceIndex) {
        this.store = store;
        this.invocationEventStore = invocationEventStore;
        this.runtimeSummaryStore = runtimeSummaryStore;
        this.qualityEvidenceRepository = qualityEvidenceRepository;
        this.benchmarkStore = benchmarkStore;
        this.skillExecutionStore = skillExecutionStore;
        this.optimizationExperimentObservationStore = optimizationExperimentObservationStore;
        this.optimizationExperimentAssessmentStore = optimizationExperimentAssessmentStore;
        this.retentionEvidenceReferenceIndex = retentionEvidenceReferenceIndex == null
                ? RetentionEvidenceReferenceIndex.empty() : retentionEvidenceReferenceIndex;
    }

    public RetentionPolicy get(Actor actor) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        return store.snapshot().retentionPolicy();
    }

    public RetentionPolicy update(RetentionPolicyMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) {
            throw new RetentionException("RETENTION_POLICY_INVALID", "Retention policy is required");
        }
        RetentionPolicy current = store.snapshot().retentionPolicy();
        if (request.policyVersion() != current.policyVersion()) {
            throw new RetentionException("RETENTION_POLICY_CONFLICT", "Retention policy version is stale");
        }
        RetentionPolicy next = new RetentionPolicy(current.policyVersion() + 1,
                request.auditRetentionDays(), request.invocationRetentionDays(),
                request.installationRetentionDays(), actor.userId(), OffsetDateTime.now(ZoneOffset.UTC));
        store.updateRetentionPolicy(next);
        audit("RETENTION_POLICY_UPDATED", actor, requestId, Map.of("policyVersion", String.valueOf(next.policyVersion())));
        return next;
    }

    public RetentionPreview preview(Actor actor, String requestId) {
        requireAdmin(actor);
        RetentionPolicy policy = store.snapshot().retentionPolicy();
        Instant now = Instant.now();
        Instant invocationCutoff = now.minus(policy.invocationRetentionDays(), ChronoUnit.DAYS);
        Instant installationCutoff = now.minus(policy.installationRetentionDays(), ChronoUnit.DAYS);
        Instant auditCutoff = now.minus(policy.auditRetentionDays(), ChronoUnit.DAYS);
        RetentionProtectionSnapshot protection = protectionSnapshot();
        long auditEligible = store.snapshot().audits().stream()
                .filter(audit -> audit.occurredAt() != null && audit.occurredAt().isBefore(auditCutoff)).count();
        long benchmarkEligible = benchmarkStore == null ? 0
                : benchmarkStore.countBefore(invocationCutoff, protection.benchmarkIds());
        long runnerExecutionEligible = skillExecutionStore == null ? 0 : skillExecutionStore.countBefore(invocationCutoff);
        long compatibilityMatrixEligible = qualityEvidenceRepository.countCompatibilityMatricesBefore(invocationCutoff,
                protection.qualityEvidenceProtection());
        long qualityEvidenceEligible = qualityEvidenceRepository.countBefore(invocationCutoff,
                protection.qualityEvidenceProtection())
                + (optimizationExperimentObservationStore == null ? 0
                : optimizationExperimentObservationStore.countBefore(invocationCutoff))
                + (optimizationExperimentAssessmentStore == null ? 0
                : optimizationExperimentAssessmentStore.countBefore(invocationCutoff));
        RetentionPreview preview = new RetentionPreview(UUID.randomUUID().toString(), policy.policyVersion(),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(PREVIEW_TTL_MINUTES), invocationCutoff,
                installationCutoff, auditCutoff, invocationEventStore.countBefore(invocationCutoff),
                store.countInstallationsBefore(installationCutoff), auditEligible,
                invocationEventStore.countBefore(invocationCutoff) * 512L
                        + store.countInstallationsBefore(installationCutoff) * 512L
                        + runtimeSummaryStore.countBefore(invocationCutoff) * 512L
                        + qualityEvidenceEligible * 512L
                + benchmarkEligible * 512L
                        + runnerExecutionEligible * 512L,
                runtimeSummaryStore.countBefore(invocationCutoff),
                qualityEvidenceEligible, benchmarkEligible, runnerExecutionEligible,
                compatibilityMatrixEligible, protection.evaluationRunIds().size(),
                protection.qualitySnapshotIds().size(), protection.benchmarkIds().size(),
                protection.compatibilityMatrixIds().size(), protection.referenceCount(), protection.fingerprint());
        previews.put(preview.previewId(), preview);
        protectionSnapshots.put(preview.previewId(), protection);
        audit("RETENTION_PREVIEW", actor, requestId, Map.of("policyVersion", String.valueOf(policy.policyVersion())));
        return preview;
    }

    public RetentionExecutionResult execute(RetentionExecutionRequest request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null || request.previewId() == null || request.previewId().isBlank()
                || request.executionId() == null || request.executionId().isBlank()) {
            throw new RetentionException("RETENTION_EXECUTION_CONFLICT", "Retention execution confirmation is invalid");
        }
        RetentionExecutionResult existing = executions.get(request.executionId());
        if (existing != null) {
            return new RetentionExecutionResult(existing.executionId(), existing.previewId(), existing.policyVersion(),
                    existing.invocationDeleted(), existing.installationDeleted(), existing.auditArchiveEligibleCount(), true,
                    existing.runtimeSummaryDeleted(), existing.qualityEvidenceDeleted(), existing.benchmarkDeleted(),
                    existing.runnerExecutionDeleted(), existing.compatibilityMatrixDeleted(),
                    existing.protectedEvaluationRunCount(), existing.protectedQualitySnapshotCount(),
                    existing.protectedBenchmarkCount(), existing.protectedCompatibilityMatrixCount(),
                    existing.protectedReferenceCount(), existing.protectionFingerprint());
        }
        RetentionPreview preview = previews.get(request.previewId());
        if (preview == null || !OffsetDateTime.now(ZoneOffset.UTC).isBefore(preview.expiresAt())) {
            throw new RetentionException("RETENTION_PREVIEW_EXPIRED", "Retention preview is expired");
        }
        if (request.policyVersion() != store.snapshot().retentionPolicy().policyVersion()
                || request.policyVersion() != preview.policyVersion()) {
            throw new RetentionException("RETENTION_EXECUTION_CONFLICT", "Retention policy changed after preview");
        }
        RetentionProtectionSnapshot frozenProtection = protectionSnapshots.get(preview.previewId());
        if (frozenProtection == null) {
            throw new RetentionException("RETENTION_PREVIEW_EXPIRED", "Retention preview is expired");
        }
        RetentionProtectionSnapshot currentProtection = protectionSnapshot();
        if (!frozenProtection.fingerprint().equals(currentProtection.fingerprint())) {
            throw new RetentionException("RETENTION_PROTECTION_CONFLICT",
                    "Evidence references changed after preview");
        }
        long invocationDeleted = invocationEventStore.deleteBefore(preview.invocationCutoff());
        long installationDeleted = store.deleteInstallationsBefore(preview.installationCutoff());
        long runtimeSummaryDeleted = runtimeSummaryStore.deleteBefore(preview.invocationCutoff());
        long qualityEvidenceDeleted = qualityEvidenceRepository.deleteBefore(preview.invocationCutoff(),
                frozenProtection.qualityEvidenceProtection())
                + (optimizationExperimentObservationStore == null ? 0
                : optimizationExperimentObservationStore.deleteBefore(preview.invocationCutoff()))
                + (optimizationExperimentAssessmentStore == null ? 0
                : optimizationExperimentAssessmentStore.deleteBefore(preview.invocationCutoff()));
        long benchmarkDeleted = benchmarkStore == null ? 0
                : benchmarkStore.deleteBefore(preview.invocationCutoff(), frozenProtection.benchmarkIds());
        long runnerExecutionDeleted = skillExecutionStore == null ? 0 : skillExecutionStore.deleteBefore(preview.invocationCutoff());
        RetentionExecutionResult result = new RetentionExecutionResult(request.executionId(), preview.previewId(),
                preview.policyVersion(), invocationDeleted, installationDeleted,
                preview.auditArchiveEligibleCount(), false, runtimeSummaryDeleted, qualityEvidenceDeleted,
                benchmarkDeleted, runnerExecutionDeleted, preview.compatibilityMatrixEligibleCount(),
                preview.protectedEvaluationRunCount(), preview.protectedQualitySnapshotCount(),
                preview.protectedBenchmarkCount(), preview.protectedCompatibilityMatrixCount(),
                preview.protectedReferenceCount(), preview.protectionFingerprint());
        executions.put(request.executionId(), result);
        audit("RETENTION_EXECUTED", actor, requestId, Map.of("executionId", request.executionId(),
                "invocationDeleted", String.valueOf(invocationDeleted),
                "runtimeSummaryDeleted", String.valueOf(runtimeSummaryDeleted),
                "qualityEvidenceDeleted", String.valueOf(qualityEvidenceDeleted),
                "compatibilityMatrixDeleted", String.valueOf(preview.compatibilityMatrixEligibleCount()),
                "benchmarkDeleted", String.valueOf(benchmarkDeleted),
                "runnerExecutionDeleted", String.valueOf(runnerExecutionDeleted),
                "installationDeleted", String.valueOf(installationDeleted)));
        return result;
    }

    private RetentionProtectionSnapshot protectionSnapshot() {
        try {
            RetentionProtectionSnapshot snapshot = retentionEvidenceReferenceIndex.snapshot();
            if (snapshot == null) {
                throw new RetentionException("RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE",
                        "Retention evidence protection is unavailable");
            }
            return snapshot;
        } catch (RetentionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RetentionException("RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE",
                    "Retention evidence protection is unavailable");
        }
    }

    private void requireAdmin(Actor actor) {
        if (actor == null || !"admin".equals(actor.role())) {
            throw new RetentionException("FORBIDDEN", "Only admin can modify or execute retention policy");
        }
    }

    private void audit(String action, Actor actor, String requestId, Map<String, String> metadata) {
        store.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "RETENTION", "policy",
                actor.userId(), actor.role(), requestId == null ? UUID.randomUUID().toString() : requestId,
                Instant.now(), metadata));
    }
}
