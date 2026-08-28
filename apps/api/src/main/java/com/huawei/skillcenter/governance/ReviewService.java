package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.StoredPackage;
import com.huawei.skillcenter.notification.NotificationRecord;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import com.huawei.skillcenter.release.ReleaseService;
import com.huawei.skillcenter.search.SkillSearchRefreshEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReviewService {
    private final GovernanceStore store;
    private final QualityReleaseGate qualityReleaseGate;
    private final ReleaseService releaseService;
    private final SkillAuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewService(GovernanceStore store) {
        this(store, (skillId, version) -> { }, null, null, event -> { });
    }

    public ReviewService(GovernanceStore store, QualityReleaseGate qualityReleaseGate) {
        this(store, qualityReleaseGate, null, null, event -> { });
    }

    public ReviewService(GovernanceStore store, QualityReleaseGate qualityReleaseGate, ReleaseService releaseService) {
        this(store, qualityReleaseGate, releaseService, null, event -> { });
    }

    public ReviewService(GovernanceStore store, QualityReleaseGate qualityReleaseGate,
                         ReleaseService releaseService, SkillAuthorizationService authorizationService) {
        this(store, qualityReleaseGate, releaseService, authorizationService, event -> { });
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewService(GovernanceStore store, QualityReleaseGate qualityReleaseGate,
                         ReleaseService releaseService, SkillAuthorizationService authorizationService,
                         ApplicationEventPublisher eventPublisher) {
        this.store = store;
        this.qualityReleaseGate = qualityReleaseGate == null ? (skillId, version) -> { } : qualityReleaseGate;
        this.releaseService = releaseService;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher == null ? event -> { } : eventPublisher;
    }

    public GovernanceSnapshot snapshot() {
        return store.snapshot();
    }

    public synchronized ReviewTask submitValidatedPackage(PackageValidationResult result, StoredPackage stored,
                                              Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("maintainer", "admin"));
        if (result == null || !result.valid() || stored == null) {
            throw new IllegalArgumentException("Validated package is required");
        }
        if (authorizationService != null) {
            authorizationService.requireSubmitVersion(result.skillId(), actor);
        }
        ensureVersionCanBeSubmitted(result.skillId(), result.version());
        Instant now = Instant.now();
        String reviewId = UUID.randomUUID().toString();
        SecurityScanEvidence securityEvidence = SecurityScanEvidence.from(result);
        SkillVersion version = new SkillVersion(
                stored.packageId(), result.skillId(), result.version(), "pending_review", result.sha256(),
                result.sizeBytes(), stored.path(), actor.userId(), now, null, null, reviewId,
                null, null, null, null, result.riskLevel(), securityEvidence);
        ReviewTask review = new ReviewTask(reviewId, stored.packageId(), result.skillId(), result.version(),
                "pending_review", actor.userId(), now, null, null, null, result.riskLevel(), null, null, null,
                securityEvidence);
        store.createPendingVersion(version, review,
                audit("PACKAGE_UPLOADED", "SKILL_VERSION", stored.packageId(), actor, requestId,
                        Map.of("skillId", result.skillId(), "version", result.version(), "status", "pending_review")));
        notify(actor.userId(), "review", "Skill 已提交审核",
                result.skillId() + " v" + result.version() + " 已进入审核队列", "upload");
        return review;
    }

    public List<ReviewTask> list(String status, Actor actor) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        String requested = status == null || status.isBlank()
                ? defaultReviewStatus(actor)
                : status;
        return store.snapshot().reviews().stream()
                .filter(review -> requested.equals(review.status())
                        || (status == null || status.isBlank()) && "admin".equals(actor.role())
                        && "security_review".equals(review.status()))
                .toList();
    }

    public ReviewTask approve(String reviewId, Actor actor, String requestId) {
        ReviewTask currentReview = findReview(reviewId);
        SkillVersion currentVersion = findVersion(currentReview.packageId());
        ensureReviewPermission(currentReview, actor);
        Instant now = Instant.now();
        if (isHighRisk(currentReview, currentVersion) && "pending_review".equals(currentReview.status())) {
            ReviewTask securityReview = new ReviewTask(currentReview.reviewId(), currentReview.packageId(), currentReview.skillId(),
                    currentReview.version(), "security_review", currentReview.submittedBy(), currentReview.submittedAt(),
                    actor.userId(), now, null, currentReview.riskLevel(), null, null, null,
                    currentReview.securityEvidence());
            SkillVersion awaitingSecurityReview = withStatus(currentVersion, "security_review", null, null, null, null, now,
                    currentVersion.riskLevel());
            store.updateReview(reviewId, securityReview, awaitingSecurityReview,
                    audit("PACKAGE_APPROVED_FOR_SECURITY_REVIEW", "SKILL_VERSION", currentVersion.packageId(), actor,
                            requestId, Map.of("skillId", currentVersion.skillId(), "version", currentVersion.version(),
                                    "status", "security_review")));
            notify(currentVersion.uploadedBy(), "review", "Skill 待安全复核",
                    currentVersion.skillId() + " v" + currentVersion.version() + " 已通过普通审核，等待安全审核", "warning");
            return securityReview;
        }
        ReleaseGateSnapshot gateSnapshot;
        try {
            gateSnapshot = qualityReleaseGate.evaluate(currentVersion.skillId(), currentVersion.version());
        } catch (QualityGateBlockedException blocked) {
            store.addAudit(audit("PACKAGE_APPROVAL_BLOCKED", "SKILL_VERSION", currentVersion.packageId(), actor,
                    requestId, Map.of("skillId", currentVersion.skillId(), "version", currentVersion.version(),
                            "status", currentReview.status(), "qualityGateReasons", String.join(",", blocked.reasons()))));
            throw blocked;
        }
        ReviewTask approved = new ReviewTask(currentReview.reviewId(), currentReview.packageId(), currentReview.skillId(),
                currentReview.version(), "approved", currentReview.submittedBy(), currentReview.submittedAt(),
                currentReview.reviewedBy(), currentReview.reviewedAt(), null, currentReview.riskLevel(), actor.userId(), now, null,
                currentReview.securityEvidence());
        SkillVersion published = withStatus(currentVersion, "published", actor.userId(), now, null, null, now,
                currentVersion.riskLevel());
        SkillSearchRefreshEvent refreshEvent = refreshEvent(published, "VERSION_PUBLISHED");
        store.updateReview(reviewId, approved, published,
                audit("PACKAGE_APPROVED", "SKILL_VERSION", currentVersion.packageId(), actor, requestId,
                        Map.of("skillId", currentVersion.skillId(), "version", currentVersion.version(), "status", "published")),
                refreshEvent);
        publishRefresh(refreshEvent);
        enrollStagingRelease(published, gateSnapshot, actor, currentReview.reviewId());
        notify(currentVersion.uploadedBy(), "review", "Skill 已发布",
                currentVersion.skillId() + " v" + currentVersion.version() + " 已进入技能市场", "check");
        return approved;
    }

    private void enrollStagingRelease(SkillVersion published, ReleaseGateSnapshot gateSnapshot,
                                      Actor actor, String reviewId) {
        if (releaseService == null) return;
        String requestId = "review:" + reviewId + ":staging";
        try {
            releaseService.requestFromApprovedVersion(published, gateSnapshot, actor, requestId);
        } catch (RuntimeException exception) {
            store.addAudit(audit("RELEASE_STAGING_ENROLLMENT_FAILED", "SKILL_VERSION", published.packageId(),
                    actor, requestId, Map.of("skillId", published.skillId(), "version", published.version(),
                            "reasonCode", "RELEASE_STAGING_ENROLLMENT_FAILED")));
        }
    }

    public ReviewTask reject(String reviewId, Actor actor, String requestId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }
        ReviewTask currentReview = findReview(reviewId);
        SkillVersion currentVersion = findVersion(currentReview.packageId());
        ensureReviewPermission(currentReview, actor);
        Instant now = Instant.now();
        boolean securityDecision = "security_review".equals(currentReview.status());
        ReviewTask rejected = new ReviewTask(currentReview.reviewId(), currentReview.packageId(), currentReview.skillId(),
                currentReview.version(), "rejected", currentReview.submittedBy(), currentReview.submittedAt(),
                currentReview.reviewedBy(), currentReview.reviewedAt(), securityDecision ? currentReview.reason() : reason.trim(),
                currentReview.riskLevel(), securityDecision ? actor.userId() : currentReview.securityReviewedBy(),
                securityDecision ? now : currentReview.securityReviewedAt(), securityDecision ? reason.trim() : currentReview.securityReason(),
                currentReview.securityEvidence());
        SkillVersion rejectedVersion = withStatus(currentVersion, "rejected", null, null, reason.trim(), actor.userId(), now,
                currentVersion.riskLevel());
        store.updateReview(reviewId, rejected, rejectedVersion,
                audit("PACKAGE_REJECTED", "SKILL_VERSION", currentVersion.packageId(), actor, requestId,
                        Map.of("skillId", currentVersion.skillId(), "version", currentVersion.version(), "status", "rejected")));
        notify(currentVersion.uploadedBy(), "review", "Skill 审核未通过",
                currentVersion.skillId() + " v" + currentVersion.version() + "：" + reason.trim(), "warning");
        return rejected;
    }

    private void notify(String userId, String type, String title, String detail, String icon) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        store.addNotification(new NotificationRecord(UUID.randomUUID().toString(), userId, type, title,
                detail, icon, Instant.now(), false, null));
    }

    private SkillSearchRefreshEvent refreshEvent(SkillVersion version, String reasonCode) {
        return new SkillSearchRefreshEvent(version.skillId(),
                SkillSearchRefreshEvent.stableSourceRevision(version.packageId(), version.version(), reasonCode),
                reasonCode);
    }

    private void publishRefresh(SkillSearchRefreshEvent event) {
        eventPublisher.publishEvent(event);
    }

    private ReviewTask findReview(String reviewId) {
        return store.snapshot().reviews().stream()
                .filter(review -> review.reviewId().equals(reviewId))
                .findFirst()
                .orElseThrow(() -> new ReviewStateConflictException("Review task not found"));
    }

    private SkillVersion findVersion(String packageId) {
        return store.snapshot().versions().stream()
                .filter(version -> version.packageId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new ReviewStateConflictException("Skill version not found"));
    }

    private void ensurePending(ReviewTask review) {
        if (!"pending_review".equals(review.status())) {
            throw new ReviewStateConflictException("Review task is no longer pending");
        }
    }

    private void ensureReviewPermission(ReviewTask review, Actor actor) {
        if (actor == null) {
            throw new ForbiddenException("Actor does not have permission for this operation");
        }
        if ("pending_review".equals(review.status())) {
            RoleGuard.require(actor, Set.of("reviewer", "admin"));
            if (actor.userId().equals(review.submittedBy())) {
                throw new ReviewStateConflictException("Submitter cannot review the same version");
            }
            return;
        }
        if ("security_review".equals(review.status())) {
            RoleGuard.require(actor, Set.of("admin"));
            if (actor.userId().equals(review.submittedBy()) || actor.userId().equals(review.reviewedBy())) {
                throw new ReviewStateConflictException("Security reviewer must be different from the submitter and ordinary reviewer");
            }
            return;
        }
        ensurePending(review);
    }

    private String defaultReviewStatus(Actor actor) {
        return "pending_review";
    }

    private void ensureVersionCanBeSubmitted(String skillId, String version) {
        SemanticVersion incoming;
        try {
            incoming = SemanticVersion.parse(version);
        } catch (IllegalArgumentException exception) {
            throw new SkillVersionConflictException("Skill version must use semantic versioning");
        }
        var existing = store.snapshot().versions().stream()
                .filter(candidate -> skillId.equals(candidate.skillId()))
                .toList();
        if (existing.stream().anyMatch(candidate -> version.equals(candidate.version()))) {
            throw new SkillVersionConflictException("Skill version already exists");
        }
        SemanticVersion latest = existing.stream()
                .map(SkillVersion::version)
                .map(this::parseExistingVersion)
                .filter(java.util.Objects::nonNull)
                .max(SemanticVersion::compareTo)
                .orElse(null);
        if (latest != null && incoming.compareTo(latest) <= 0) {
            throw new SkillVersionConflictException("Skill version must be newer than the latest version");
        }
    }

    private SemanticVersion parseExistingVersion(String version) {
        try {
            return SemanticVersion.parse(version);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isHighRisk(ReviewTask review, SkillVersion version) {
        return "high".equalsIgnoreCase(review.riskLevel()) || "high".equalsIgnoreCase(version.riskLevel());
    }

    private SkillVersion withStatus(SkillVersion current, String status, String publishedBy, Instant publishedAt,
                                    String statusReason, String statusChangedBy, Instant statusChangedAt, String riskLevel) {
        return new SkillVersion(current.packageId(), current.skillId(), current.version(), status,
                current.sha256(), current.sizeBytes(), current.artifactPath(), current.uploadedBy(), current.uploadedAt(),
                publishedBy, publishedAt, current.reviewId(), statusReason, current.replacementVersion(),
                statusChangedBy, statusChangedAt, riskLevel, current.securityEvidence());
    }

    private AuditEvent audit(String action, String resourceType, String resourceId, Actor actor,
                             String requestId, Map<String, String> metadata) {
        return new AuditEvent(UUID.randomUUID().toString(), action, resourceType, resourceId,
                actor.userId(), actor.role(), requestId, Instant.now(), metadata);
    }
}
