package com.huawei.skillcenter.release;

import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillVisibilityContext;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.QualityGateBlockedException;
import com.huawei.skillcenter.governance.QualityReleaseGate;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.operations.PlatformReadiness;
import com.huawei.skillcenter.operations.ProductionReadinessGate;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessment;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReleaseService {
    private final GovernanceStore governanceStore;
    private final QualityReleaseGate qualityReleaseGate;
    private final ReleaseRecordRepository releaseStore;
    private final ReleaseTarget releaseTarget;
    private final OptimizationExperimentAssessmentRepository assessmentStore;
    private final SkillAuthorizationService authorizationService;
    private final ProductionReadinessGate productionReadinessGate;
    private final Clock clock;

    @Autowired
    public ReleaseService(GovernanceStore governanceStore, QualityReleaseGate qualityReleaseGate,
                          ReleaseRecordRepository releaseStore, ReleaseTarget releaseTarget,
                          OptimizationExperimentAssessmentRepository assessmentStore,
                          SkillAuthorizationService authorizationService,
                          ProductionReadinessGate productionReadinessGate) {
        this(governanceStore, qualityReleaseGate, releaseStore, releaseTarget, assessmentStore,
                authorizationService, productionReadinessGate, Clock.systemUTC());
    }

    public ReleaseService(GovernanceStore governanceStore, QualityReleaseGate qualityReleaseGate,
                          ReleaseRecordRepository releaseStore, ReleaseTarget releaseTarget,
                          OptimizationExperimentAssessmentRepository assessmentStore, Clock clock) {
        this(governanceStore, qualityReleaseGate, releaseStore, releaseTarget, assessmentStore,
                null, null, clock);
    }

    public ReleaseService(GovernanceStore governanceStore, QualityReleaseGate qualityReleaseGate,
                          ReleaseRecordRepository releaseStore, ReleaseTarget releaseTarget,
                          OptimizationExperimentAssessmentRepository assessmentStore,
                          SkillAuthorizationService authorizationService, Clock clock) {
        this(governanceStore, qualityReleaseGate, releaseStore, releaseTarget, assessmentStore,
                authorizationService, null, clock);
    }

    public ReleaseService(GovernanceStore governanceStore, QualityReleaseGate qualityReleaseGate,
                          ReleaseRecordRepository releaseStore, ReleaseTarget releaseTarget,
                          OptimizationExperimentAssessmentRepository assessmentStore,
                          SkillAuthorizationService authorizationService,
                          ProductionReadinessGate productionReadinessGate, Clock clock) {
        this.governanceStore = require(governanceStore, "governanceStore");
        this.qualityReleaseGate = require(qualityReleaseGate, "qualityReleaseGate");
        this.releaseStore = require(releaseStore, "releaseStore");
        this.releaseTarget = require(releaseTarget, "releaseTarget");
        this.assessmentStore = require(assessmentStore, "assessmentStore");
        this.authorizationService = authorizationService;
        this.productionReadinessGate = productionReadinessGate;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        normalizeInFlightRecords();
    }

    @PostConstruct
    void onStartup() {
        normalizeInFlightRecords();
    }

    public synchronized ReleaseRecord request(ReleaseRequest request, Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("maintainer", "admin"));
        if (request == null) throw new IllegalArgumentException("release request is required");
        if (authorizationService != null) {
            authorizationService.requireManage(request.skillId(), actor);
        }
        var existing = releaseStore.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            ReleaseRecord value = existing.get();
            if (!sameRequest(value, request)) throw new ReleaseConflictException("idempotencyKey is already bound to another release");
            return value;
        }
        SkillVersion version = findVersion(request.skillId(), request.version());
        ReleaseGateSnapshot gateSnapshot = qualityReleaseGate.evaluate(request.skillId(), request.version());
        if ("BLOCKED".equals(gateSnapshot.outcome())) {
            throw new QualityGateBlockedException(request.skillId(), request.version(), gateSnapshot.reasonCodes());
        }
        if (request.targetEnvironment() == ReleaseEnvironment.PRODUCTION && "NO_EVIDENCE".equals(gateSnapshot.outcome())) {
            throw new QualityGateBlockedException(request.skillId(), request.version(), List.of("QUALITY_EVIDENCE_REQUIRED"));
        }
        if (request.targetEnvironment() == ReleaseEnvironment.PRODUCTION) {
            requireProductionReadiness(request.skillId(), request.version());
        }
        Instant now = clock.instant();
        ReleaseRecord record = ReleaseRecord.request(UUID.randomUUID().toString().replace("-", ""),
                version.skillId(), version.version(), version.sha256(), request.targetEnvironment(), gateSnapshot,
                request.idempotencyKey(), actor.userId(), now);
        if (!request.sourceAssessmentId().isBlank()) {
            record = withSourceAssessment(record, request.sourceAssessmentId());
        }
        ReleaseRecord created = releaseStore.create(record);
        audit("RELEASE_REQUESTED", created, actor, requestId, Map.of("status", created.status().name()));
        return created;
    }

    /** Enrolls a review-approved version without re-evaluating the frozen quality gate. */
    public synchronized ReleaseRecord requestFromApprovedVersion(SkillVersion version,
                                                                  ReleaseGateSnapshot gateSnapshot,
                                                                  Actor actor, String requestId) {
        if (version == null) throw new IllegalArgumentException("approved version is required");
        if (gateSnapshot == null) throw new IllegalArgumentException("gateSnapshot is required");
        if (actor == null) throw new IllegalArgumentException("actor is required");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        var existing = releaseStore.findByIdempotencyKey(requestId);
        if (existing.isPresent()) {
            ReleaseRecord value = existing.get();
            if (!value.skillId().equals(version.skillId()) || !value.version().equals(version.version())
                    || !value.sha256().equals(version.sha256())
                    || value.targetEnvironment() != ReleaseEnvironment.STAGING
                    || !value.gateSnapshot().equals(gateSnapshot)) {
                throw new ReleaseConflictException("idempotencyKey is already bound to another release");
            }
            return value;
        }
        Instant now = clock.instant();
        ReleaseRecord created = releaseStore.create(ReleaseRecord.request(
                UUID.randomUUID().toString().replace("-", ""), version.skillId(), version.version(), version.sha256(),
                ReleaseEnvironment.STAGING, gateSnapshot, requestId, actor.userId(), now));
        audit("RELEASE_STAGING_ENROLLED", created, actor, requestId,
                Map.of("status", created.status().name()));
        return created;
    }

    public List<ReleaseRecord> list(String skillId, String version, ReleaseEnvironment environment,
                                    ReleaseStatus status, Actor actor) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        if (authorizationService != null && skillId != null && !skillId.isBlank()) {
            authorizationService.requireVisible(skillId, actor, SkillVisibilityContext.GOVERNANCE);
        }
        return releaseStore.findAll(skillId, version, environment, status);
    }

    public ReleaseRecord find(String releaseId, Actor actor) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        ReleaseRecord record = findRecord(releaseId);
        if (authorizationService != null) {
            authorizationService.requireVisible(record.skillId(), actor, SkillVisibilityContext.GOVERNANCE);
        }
        return record;
    }

    public synchronized ReleaseRecord approve(String releaseId, Actor actor, String requestId) {
        ReleaseRecord current = findRecord(releaseId);
        ensureApprovalPermission(current, actor);
        try {
            ReleaseRecord approved = releaseStore.replace(current.approve(actor.userId(), clock.instant()));
            audit("RELEASE_APPROVED", approved, actor, requestId, Map.of("status", approved.status().name()));
            return approved;
        } catch (IllegalStateException exception) {
            throw new ReleaseInvalidStateException("Release cannot be approved in its current state");
        }
    }

    public synchronized ReleaseRecord reject(String releaseId, ReleaseRejectionRequest request,
                                              Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        ReleaseRecord current = findRecord(releaseId);
        try {
            ReleaseRecord rejected = releaseStore.replace(current.reject(actor.userId(), request.reason(), clock.instant()));
            audit("RELEASE_REJECTED", rejected, actor, requestId, Map.of("status", rejected.status().name()));
            return rejected;
        } catch (IllegalStateException exception) {
            throw new ReleaseInvalidStateException("Release cannot be rejected in its current state");
        }
    }

    public synchronized ReleaseRecord promote(String releaseId, Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        ReleaseRecord current = findRecord(releaseId);
        if (current.targetEnvironment() == ReleaseEnvironment.PRODUCTION) {
            requireProductionReadiness(current.skillId(), current.version());
        }
        ReleaseRecord promoting;
        try {
            promoting = releaseStore.replace(current.promoting("", clock.instant()));
            audit("RELEASE_PROMOTION_STARTED", promoting, actor, requestId,
                    Map.of("status", promoting.status().name()));
        } catch (IllegalStateException exception) {
            throw new ReleaseInvalidStateException("Release cannot be promoted in its current state");
        }
        ReleaseTargetResult result;
        try {
            result = releaseTarget.promote(promoting);
        } catch (RuntimeException exception) {
            return failTarget(promoting, "RELEASE_TARGET_FAILED", actor, requestId, exception);
        }
        if (result == null || !result.success()) {
            return failTarget(promoting, result == null ? "RELEASE_TARGET_FAILED" : result.reasonCode(),
                    actor, requestId, null);
        }
        ReleaseRecord promoted = releaseStore.replace(promoting.promoted(result.reference(), clock.instant()));
        audit("RELEASE_PROMOTED", promoted, actor, requestId, Map.of("status", promoted.status().name()));
        return promoted;
    }

    public synchronized ReleaseRecord rollbackReview(String releaseId, RollbackReviewRequest request,
                                                      Actor actor, String requestId) {
        ReleaseRecord current = findRecord(releaseId);
        ensureApprovalPermission(current, actor);
        if (!request.assessmentId().isBlank()) {
            OptimizationExperimentAssessment assessment = assessmentStore.find(request.assessmentId())
                    .orElseThrow(() -> new ReleaseInvalidStateException("Rollback assessment was not found"));
            if (!current.skillId().equals(assessment.skillId())
                    || !current.version().equals(assessment.candidateVersion())) {
                throw new ReleaseInvalidStateException("Rollback assessment does not match the release");
            }
        }
        try {
            ReleaseRecord reviewed = releaseStore.replace(current.rollbackReview(actor.userId(), request.reason(),
                    request.assessmentId(), request.targetVersion(), request.targetReleaseId(), clock.instant()));
            audit("RELEASE_ROLLBACK_REVIEW_REQUESTED", reviewed, actor, requestId,
                    Map.of("status", reviewed.status().name()));
            return reviewed;
        } catch (IllegalStateException exception) {
            throw new ReleaseInvalidStateException("Release cannot enter rollback review in its current state");
        }
    }

    public synchronized ReleaseRecord rollback(String releaseId, Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        ReleaseRecord current = findRecord(releaseId);
        ReleaseRecord rollingBack;
        try {
            rollingBack = releaseStore.replace(current.rollingBack(clock.instant()));
            audit("RELEASE_ROLLBACK_STARTED", rollingBack, actor, requestId,
                    Map.of("status", rollingBack.status().name()));
        } catch (IllegalStateException exception) {
            throw new ReleaseInvalidStateException("Release cannot be rolled back in its current state");
        }
        ReleaseTargetResult result;
        try {
            result = releaseTarget.rollback(rollingBack);
        } catch (RuntimeException exception) {
            return failTarget(rollingBack, "RELEASE_TARGET_FAILED", actor, requestId, exception);
        }
        if (result == null || !result.success()) {
            return failTarget(rollingBack, result == null ? "RELEASE_TARGET_FAILED" : result.reasonCode(),
                    actor, requestId, null);
        }
        ReleaseRecord rolledBack = releaseStore.replace(rollingBack.rolledBack(result.reference(), clock.instant()));
        audit("RELEASE_ROLLED_BACK", rolledBack, actor, requestId, Map.of("status", rolledBack.status().name()));
        return rolledBack;
    }

    private ReleaseRecord failTarget(ReleaseRecord current, String reasonCode, Actor actor,
                                     String requestId, RuntimeException cause) {
        ReleaseRecord failed = releaseStore.replace(current.failed(reasonCode, current.targetReference(), clock.instant()));
        audit("RELEASE_EXECUTION_FAILED", failed, actor, requestId,
                Map.of("status", failed.status().name(), "reasonCode", failed.statusReason()));
        throw cause == null ? new ReleaseTargetException(failed.statusReason())
                : new ReleaseTargetException(failed.statusReason());
    }

    private void ensureApprovalPermission(ReleaseRecord record, Actor actor) {
        if (record.targetEnvironment() == ReleaseEnvironment.STAGING) {
            RoleGuard.require(actor, Set.of("reviewer", "admin"));
        } else {
            RoleGuard.require(actor, Set.of("admin"));
        }
    }

    private boolean sameRequest(ReleaseRecord record, ReleaseRequest request) {
        return record.skillId().equals(request.skillId()) && record.version().equals(request.version())
                && record.targetEnvironment() == request.targetEnvironment()
                && record.sourceAssessmentId().equals(request.sourceAssessmentId());
    }

    private SkillVersion findVersion(String skillId, String version) {
        return governanceStore.snapshot().versions().stream()
                .filter(candidate -> skillId.equals(candidate.skillId()) && version.equals(candidate.version()))
                .findFirst()
                .orElseThrow(() -> new ReleaseNotFoundException(skillId + ":" + version));
    }

    private ReleaseRecord findRecord(String releaseId) {
        return releaseStore.find(releaseId).orElseThrow(() -> new ReleaseNotFoundException(releaseId));
    }

    private void audit(String action, ReleaseRecord release, Actor actor, String requestId, Map<String, String> metadata) {
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "RELEASE",
                release.releaseId(), actor.userId(), actor.role(), requestId, clock.instant(),
                Map.of("skillId", release.skillId(), "version", release.version(),
                        "targetEnvironment", release.targetEnvironment().name(), "releaseStatus", release.status().name(),
                        "releaseId", release.releaseId(), "actionStatus", metadata.getOrDefault("status", ""),
                        "reasonCode", metadata.getOrDefault("reasonCode", ""))));
    }

    private void normalizeInFlightRecords() {
        Instant now = clock.instant();
        releaseStore.findAll(null, null, null, null).stream()
                .filter(value -> value.status() == ReleaseStatus.PROMOTING || value.status() == ReleaseStatus.ROLLING_BACK)
                .forEach(value -> releaseStore.replace(value.failed("RELEASE_EXECUTION_UNKNOWN", value.targetReference(), now)));
    }

    private ReleaseRecord withSourceAssessment(ReleaseRecord record, String sourceAssessmentId) {
        return new ReleaseRecord(record.releaseId(), record.skillId(), record.version(), record.sha256(),
                record.targetEnvironment(), record.gateSnapshot(), sourceAssessmentId, record.rollbackOfReleaseId(),
                record.rollbackTargetVersion(), record.rollbackTargetReleaseId(), record.rollbackAssessmentId(),
                record.idempotencyKey(), record.status(), record.requestedBy(), record.requestedAt(), record.approvedBy(),
                record.approvedAt(), record.statusReason(), record.targetReference(), record.startedAt(),
                record.completedAt(), record.updatedBy(), record.updatedAt());
    }

    private void requireProductionReadiness(String skillId, String version) {
        if (productionReadinessGate == null) {
            throw new QualityGateBlockedException(skillId, version,
                    List.of("PLATFORM_PRODUCTION_READINESS_BLOCKED",
                            "PLATFORM_PRODUCTION_READINESS_UNAVAILABLE"));
        }
        PlatformReadiness readiness;
        try {
            readiness = productionReadinessGate.readiness();
        } catch (RuntimeException exception) {
            throw new QualityGateBlockedException(skillId, version,
                    List.of("PLATFORM_PRODUCTION_READINESS_BLOCKED",
                            "PLATFORM_PRODUCTION_READINESS_UNAVAILABLE"));
        }
        if (readiness != null && "READY".equals(readiness.overall())) {
            return;
        }
        List<String> reasons = new java.util.ArrayList<>();
        reasons.add("PLATFORM_PRODUCTION_READINESS_BLOCKED");
        if (readiness != null) {
            readiness.blockingReasonCodes().stream()
                    .filter(reason -> reason != null && !reason.isBlank())
                    .forEach(reasons::add);
        }
        if (reasons.size() == 1) {
            reasons.add("PLATFORM_PRODUCTION_READINESS_NOT_READY");
        }
        throw new QualityGateBlockedException(skillId, version, reasons);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
