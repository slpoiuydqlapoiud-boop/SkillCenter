package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.StoredPackage;
import com.huawei.skillcenter.notification.NotificationRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReviewService {
    private final GovernanceStore store;

    public ReviewService(GovernanceStore store) {
        this.store = store;
    }

    public GovernanceSnapshot snapshot() {
        return store.snapshot();
    }

    public ReviewTask submitValidatedPackage(PackageValidationResult result, StoredPackage stored,
                                              Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("maintainer", "admin"));
        if (result == null || !result.valid() || stored == null) {
            throw new IllegalArgumentException("Validated package is required");
        }
        Instant now = Instant.now();
        String reviewId = UUID.randomUUID().toString();
        SkillVersion version = new SkillVersion(
                stored.packageId(), result.skillId(), result.version(), "pending_review", result.sha256(),
                result.sizeBytes(), stored.path(), actor.userId(), now, null, null, reviewId);
        ReviewTask review = new ReviewTask(reviewId, stored.packageId(), result.skillId(), result.version(),
                "pending_review", actor.userId(), now, null, null, null);
        store.createPendingVersion(version, review,
                audit("PACKAGE_UPLOADED", "SKILL_VERSION", stored.packageId(), actor, requestId,
                        Map.of("skillId", result.skillId(), "version", result.version(), "status", "pending_review")));
        notify(actor.userId(), "review", "Skill 已提交审核",
                result.skillId() + " v" + result.version() + " 已进入审核队列", "upload");
        return review;
    }

    public List<ReviewTask> list(String status, Actor actor) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        String requested = status == null || status.isBlank() ? "pending_review" : status;
        return store.snapshot().reviews().stream()
                .filter(review -> requested.equals(review.status()))
                .toList();
    }

    public ReviewTask approve(String reviewId, Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        ReviewTask currentReview = findReview(reviewId);
        SkillVersion currentVersion = findVersion(currentReview.packageId());
        ensurePending(currentReview);
        Instant now = Instant.now();
        ReviewTask approved = new ReviewTask(currentReview.reviewId(), currentReview.packageId(), currentReview.skillId(),
                currentReview.version(), "approved", currentReview.submittedBy(), currentReview.submittedAt(),
                actor.userId(), now, null);
        SkillVersion published = new SkillVersion(currentVersion.packageId(), currentVersion.skillId(), currentVersion.version(),
                "published", currentVersion.sha256(), currentVersion.sizeBytes(), currentVersion.artifactPath(),
                currentVersion.uploadedBy(), currentVersion.uploadedAt(), actor.userId(), now, currentVersion.reviewId());
        store.updateReview(reviewId, approved, published,
                audit("PACKAGE_APPROVED", "SKILL_VERSION", currentVersion.packageId(), actor, requestId,
                        Map.of("skillId", currentVersion.skillId(), "version", currentVersion.version(), "status", "published")));
        notify(currentVersion.uploadedBy(), "review", "Skill 已发布",
                currentVersion.skillId() + " v" + currentVersion.version() + " 已进入技能市场", "check");
        return approved;
    }

    public ReviewTask reject(String reviewId, Actor actor, String requestId, String reason) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }
        ReviewTask currentReview = findReview(reviewId);
        SkillVersion currentVersion = findVersion(currentReview.packageId());
        ensurePending(currentReview);
        Instant now = Instant.now();
        ReviewTask rejected = new ReviewTask(currentReview.reviewId(), currentReview.packageId(), currentReview.skillId(),
                currentReview.version(), "rejected", currentReview.submittedBy(), currentReview.submittedAt(),
                actor.userId(), now, reason.trim());
        SkillVersion rejectedVersion = new SkillVersion(currentVersion.packageId(), currentVersion.skillId(), currentVersion.version(),
                "rejected", currentVersion.sha256(), currentVersion.sizeBytes(), currentVersion.artifactPath(),
                currentVersion.uploadedBy(), currentVersion.uploadedAt(), null, null, currentVersion.reviewId());
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

    private AuditEvent audit(String action, String resourceType, String resourceId, Actor actor,
                             String requestId, Map<String, String> metadata) {
        return new AuditEvent(UUID.randomUUID().toString(), action, resourceType, resourceId,
                actor.userId(), actor.role(), requestId, Instant.now(), metadata);
    }
}
