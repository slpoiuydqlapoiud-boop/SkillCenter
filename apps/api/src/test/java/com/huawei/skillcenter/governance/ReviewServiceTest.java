package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.PackageSecurityFinding;
import com.huawei.skillcenter.packageupload.StoredPackage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewServiceTest {
    @Test
    void submitCreatesPendingReviewAndApprovePublishesVersion() throws Exception {
        Path state = Files.createTempDirectory("governance-review-").resolve("state.json");
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()));
        PackageValidationResult result = new PackageValidationResult(true, "demo-skill", "1.0.0", "abc", 42, List.of());

        ReviewTask submitted = service.submitValidatedPackage(result,
                new StoredPackage("package-1", "C:/packages/package-1.zip"),
                new Actor("alice", "maintainer"), "req-1");
        assertEquals("pending_review", submitted.status());

        assertThrows(ReviewStateConflictException.class,
                () -> service.approve(submitted.reviewId(), new Actor("alice", "reviewer"), "req-self-review"));

        service.approve(submitted.reviewId(), new Actor("bob", "reviewer"), "req-2");
        GovernanceSnapshot snapshot = service.snapshot();
        assertEquals("approved", snapshot.reviews().get(0).status());
        assertEquals("published", snapshot.versions().get(0).status());
        assertEquals("bob", snapshot.versions().get(0).publishedBy());
    }

    @Test
    void rejectRequiresReasonAndMarksVersionRejected() throws Exception {
        Path state = Files.createTempDirectory("governance-reject-").resolve("state.json");
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()));
        ReviewTask submitted = service.submitValidatedPackage(
                new PackageValidationResult(true, "demo-skill", "1.0.0", "abc", 42, List.of()),
                new StoredPackage("package-1", "C:/packages/package-1.zip"),
                new Actor("alice", "maintainer"), "req-1");

        assertThrows(IllegalArgumentException.class,
                () -> service.reject(submitted.reviewId(), new Actor("bob", "reviewer"), "req-2", " "));
        service.reject(submitted.reviewId(), new Actor("bob", "reviewer"), "req-3", "metadata is incomplete");
        assertEquals("rejected", service.snapshot().versions().get(0).status());
    }

    @Test
    void rejectsDuplicateAndNonIncreasingVersionsBeforeCreatingReview() throws Exception {
        Path state = Files.createTempDirectory("governance-version-conflict-").resolve("state.json");
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()));
        PackageValidationResult first = new PackageValidationResult(true, "versioned-skill", "1.2.0", "abc", 42, List.of());
        service.submitValidatedPackage(first,
                new StoredPackage("package-version-1", "C:/packages/package-version-1.zip"),
                new Actor("alice", "developer"), "req-version-1");

        assertThrows(SkillVersionConflictException.class, () -> service.submitValidatedPackage(
                new PackageValidationResult(true, "versioned-skill", "1.2.0", "def", 43, List.of()),
                new StoredPackage("package-version-duplicate", "C:/packages/package-version-duplicate.zip"),
                new Actor("alice", "developer"), "req-version-duplicate"));
        assertThrows(SkillVersionConflictException.class, () -> service.submitValidatedPackage(
                new PackageValidationResult(true, "versioned-skill", "1.1.9", "ghi", 44, List.of()),
                new StoredPackage("package-version-regression", "C:/packages/package-version-regression.zip"),
                new Actor("alice", "developer"), "req-version-regression"));

        assertEquals(1, service.snapshot().versions().size());
        assertEquals(1, service.snapshot().reviews().size());
    }

    @Test
    void highRiskVersionRequiresDifferentSecurityReviewerBeforePublishing() throws Exception {
        Path state = Files.createTempDirectory("governance-high-risk-").resolve("state.json");
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()));
        PackageValidationResult result = new PackageValidationResult(true, "high-risk-skill", "1.0.0", "abc", 42,
                List.of(), "high");

        ReviewTask submitted = service.submitValidatedPackage(result,
                new StoredPackage("package-high-risk", "C:/packages/package-high-risk.zip"),
                new Actor("alice", "maintainer"), "req-high-risk-1");

        ReviewTask securityReview = service.approve(submitted.reviewId(), new Actor("bob", "reviewer"),
                "req-high-risk-2");
        assertEquals("security_review", securityReview.status());
        assertEquals("security_review", service.snapshot().versions().get(0).status());
        assertEquals(1, service.list(null, new Actor("carol", "admin")).size());
        assertEquals(0, service.list(null, new Actor("bob", "reviewer")).size());

        assertThrows(ReviewStateConflictException.class,
                () -> service.approve(submitted.reviewId(), new Actor("bob", "admin"),
                        "req-high-risk-same-reviewer"));

        ReviewTask approved = service.approve(submitted.reviewId(), new Actor("carol", "admin"),
                "req-high-risk-3");
        assertEquals("approved", approved.status());
        assertEquals("published", service.snapshot().versions().get(0).status());
        assertEquals("bob", approved.reviewedBy());
        assertEquals("carol", approved.securityReviewedBy());

        GovernanceStore restarted = new GovernanceStore(state, List.of());
        ReviewTask restored = restarted.snapshot().reviews().get(0);
        assertEquals("high", restored.riskLevel());
        assertEquals("bob", restored.reviewedBy());
        assertEquals("carol", restored.securityReviewedBy());
        assertEquals("high", restarted.snapshot().versions().get(0).riskLevel());
    }

    @Test
    void persistsSecurityScanProvenanceThroughReviewApprovalAndRestart() throws Exception {
        Path state = Files.createTempDirectory("governance-security-evidence-").resolve("state.json");
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()));
        PackageValidationResult result = new PackageValidationResult(true, "evidence-skill", "1.0.0", "abc", 42,
                List.of(), "low", "PASSED", List.of());

        ReviewTask submitted = service.submitValidatedPackage(result,
                new StoredPackage("package-security-evidence", "C:/packages/package-security-evidence.zip"),
                new Actor("alice", "maintainer"), "req-security-evidence");

        assertEquals("PASSED", submitted.securityEvidence().status());
        assertEquals("local-package-security", submitted.securityEvidence().scannerId());
        assertEquals("1", submitted.securityEvidence().scannerVersion());
        assertEquals("PASSED", service.snapshot().versions().get(0).securityEvidence().status());

        service.approve(submitted.reviewId(), new Actor("bob", "reviewer"), "req-security-approve");
        GovernanceStore restarted = new GovernanceStore(state, List.of());
        assertEquals("PASSED", restarted.snapshot().reviews().get(0).securityEvidence().status());
        assertEquals("local-package-security", restarted.snapshot().versions().get(0).securityEvidence().scannerId());
    }

    @Test
    void persistsOnlySafeSecurityFindingSummaries() throws Exception {
        Path state = Files.createTempDirectory("governance-security-finding-").resolve("state.json");
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()));
        PackageValidationResult result = new PackageValidationResult(true, "finding-skill", "1.0.0", "abc", 42,
                List.of(), "low", "PASSED", List.of(
                new PackageSecurityFinding("REVIEW_NOTE", "finding-skill/SKILL.md", "LOW", "safe summary")));

        ReviewTask submitted = service.submitValidatedPackage(result,
                new StoredPackage("package-security-finding", "C:/packages/package-security-finding.zip"),
                new Actor("alice", "maintainer"), "req-security-finding");

        assertEquals("REVIEW_NOTE", submitted.securityEvidence().findings().get(0).code());
        assertEquals("finding-skill/SKILL.md", submitted.securityEvidence().findings().get(0).path());
        assertEquals("LOW", submitted.securityEvidence().findings().get(0).severity());
        assertEquals("REVIEW_NOTE", service.snapshot().versions().get(0).securityEvidence().findings().get(0).code());
    }

    @Test
    void releaseGateBlockLeavesReviewAndVersionPending() throws Exception {
        Path state = Files.createTempDirectory("governance-optimization-gate-").resolve("state.json");
        QualityGateBlockedException blocked = new QualityGateBlockedException("optimization-skill", "1.1.0",
                List.of("OPTIMIZATION_DECISION_REQUIRED"));
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()),
                (skillId, version) -> { throw blocked; });
        ReviewTask submitted = service.submitValidatedPackage(
                new PackageValidationResult(true, "optimization-skill", "1.1.0", "abc", 42, List.of()),
                new StoredPackage("package-optimization", "C:/packages/package-optimization.zip"),
                new Actor("alice", "maintainer"), "req-optimization-submit");

        assertThrows(QualityGateBlockedException.class,
                () -> service.approve(submitted.reviewId(), new Actor("bob", "reviewer"), "req-optimization-approve"));
        assertEquals("pending_review", service.snapshot().reviews().get(0).status());
        assertEquals("pending_review", service.snapshot().versions().get(0).status());
        assertEquals(null, service.snapshot().versions().get(0).publishedAt());
    }

    @Test
    void releaseGateBlockIsPersistedAsAuditableAttemptWithoutPublishing() throws Exception {
        Path state = Files.createTempDirectory("governance-release-gate-audit-").resolve("state.json");
        QualityGateBlockedException blocked = new QualityGateBlockedException("audited-skill", "2.0.0",
                List.of("OPTIMIZATION_DECISION_REQUIRED", "QUALITY_SNAPSHOT_MISSING"));
        ReviewService service = new ReviewService(new GovernanceStore(state, List.of()),
                (skillId, version) -> { throw blocked; });
        ReviewTask submitted = service.submitValidatedPackage(
                new PackageValidationResult(true, "audited-skill", "2.0.0", "abc", 42, List.of()),
                new StoredPackage("package-audited", "C:/packages/package-audited.zip"),
                new Actor("alice", "maintainer"), "req-audited-submit");

        assertThrows(QualityGateBlockedException.class,
                () -> service.approve(submitted.reviewId(), new Actor("bob", "reviewer"), "req-audited-approve"));

        AuditEvent audit = service.snapshot().audits().stream()
                .filter(event -> "PACKAGE_APPROVAL_BLOCKED".equals(event.action()))
                .findFirst()
                .orElseThrow();
        assertEquals("SKILL_VERSION", audit.resourceType());
        assertEquals("package-audited", audit.resourceId());
        assertEquals("bob", audit.actorId());
        assertEquals("reviewer", audit.actorRole());
        assertEquals("req-audited-approve", audit.requestId());
        assertEquals("audited-skill", audit.metadata().get("skillId"));
        assertEquals("2.0.0", audit.metadata().get("version"));
        assertEquals("pending_review", audit.metadata().get("status"));
        assertEquals("OPTIMIZATION_DECISION_REQUIRED,QUALITY_SNAPSHOT_MISSING", audit.metadata().get("qualityGateReasons"));
        assertEquals("pending_review", service.snapshot().reviews().get(0).status());
        assertEquals("pending_review", service.snapshot().versions().get(0).status());

        GovernanceStore restarted = new GovernanceStore(state, List.of());
        assertEquals(2, restarted.snapshot().audits().size());
        assertEquals("PACKAGE_APPROVAL_BLOCKED", restarted.snapshot().audits().get(1).action());
        assertEquals("req-audited-approve", restarted.snapshot().audits().get(1).requestId());
    }
}
