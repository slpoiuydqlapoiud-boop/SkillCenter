package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.packageupload.PackageValidationResult;
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
}
