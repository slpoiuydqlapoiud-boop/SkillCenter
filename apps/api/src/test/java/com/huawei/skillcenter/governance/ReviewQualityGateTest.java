package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.StoredPackage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewQualityGateTest {
    @Test
    void blockedQualityGatePreventsPublishingAndLeavesReviewPending() throws Exception {
        Path state = Files.createTempDirectory("governance-quality-gate-").resolve("state.json");
        GovernanceStore store = new GovernanceStore(state, List.of());
        ReviewService service = new ReviewService(store,
                (skillId, version) -> {
                    throw new QualityGateBlockedException(skillId, version, List.of("SCORE_BELOW_THRESHOLD"));
                });
        ReviewTask submitted = service.submitValidatedPackage(
                new PackageValidationResult(true, "demo-skill", "1.0.0", "abc", 42, List.of()),
                new StoredPackage("package-1", "C:/packages/package-1.zip"),
                new Actor("alice", "maintainer"), "req-1");

        assertThatThrownBy(() -> service.approve(submitted.reviewId(), new Actor("bob", "reviewer"), "req-2"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("SCORE_BELOW_THRESHOLD");
        assertThat(store.snapshot().reviews().get(0).status()).isEqualTo("pending_review");
        assertThat(store.snapshot().versions().get(0).status()).isEqualTo("pending_review");
    }
}
