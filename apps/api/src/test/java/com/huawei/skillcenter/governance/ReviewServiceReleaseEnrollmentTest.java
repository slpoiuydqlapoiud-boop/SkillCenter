package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.packageupload.PackageValidationResult;
import com.huawei.skillcenter.packageupload.StoredPackage;
import com.huawei.skillcenter.release.ReleaseGateSnapshot;
import com.huawei.skillcenter.release.ReleaseService;
import com.huawei.skillcenter.release.ReleaseTargetException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceReleaseEnrollmentTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @Test
    void finalApprovalEnrollsStagingWithFrozenGateSnapshot() throws Exception {
        GovernanceStore store = new GovernanceStore(tempState("enroll"), List.of());
        QualityReleaseGate gate = mock(QualityReleaseGate.class);
        ReleaseService releases = mock(ReleaseService.class);
        ReleaseGateSnapshot snapshot = ReleaseGateSnapshot.passed(NOW);
        when(gate.evaluate("enrollment-skill", "1.0.0")).thenReturn(snapshot);
        ReviewService service = new ReviewService(store, gate, releases);

        ReviewTask submitted = submit(service, "enrollment-skill", "1.0.0", "abc");
        ReviewTask approved = service.approve(submitted.reviewId(), new Actor("reviewer", "reviewer"), "approve-1");

        assertThat(approved.status()).isEqualTo("approved");
        verify(releases).requestFromApprovedVersion(any(SkillVersion.class), eq(snapshot),
                eq(new Actor("reviewer", "reviewer")), eq("review:" + submitted.reviewId() + ":staging"));
    }

    @Test
    void enrollmentFailureLeavesApprovalCommittedAndWritesSafeAudit() throws Exception {
        GovernanceStore store = new GovernanceStore(tempState("enroll-failure"), List.of());
        QualityReleaseGate gate = mock(QualityReleaseGate.class);
        ReleaseService releases = mock(ReleaseService.class);
        when(gate.evaluate("failure-skill", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));
        when(releases.requestFromApprovedVersion(any(), any(), any(), any()))
                .thenThrow(new ReleaseTargetException("TARGET_SECRET_NOT_EXPOSED"));
        ReviewService service = new ReviewService(store, gate, releases);

        ReviewTask submitted = submit(service, "failure-skill", "1.0.0", "abc");
        ReviewTask approved = service.approve(submitted.reviewId(), new Actor("reviewer", "reviewer"), "approve-2");

        assertThat(approved.status()).isEqualTo("approved");
        assertThat(service.snapshot().versions().get(0).status()).isEqualTo("published");
        AuditEvent audit = service.snapshot().audits().stream()
                .filter(value -> "RELEASE_STAGING_ENROLLMENT_FAILED".equals(value.action()))
                .findFirst().orElseThrow();
        assertThat(audit.metadata()).containsEntry("skillId", "failure-skill")
                .containsEntry("version", "1.0.0")
                .containsEntry("reasonCode", "RELEASE_STAGING_ENROLLMENT_FAILED");
        assertThat(audit.metadata().toString()).doesNotContain("TARGET_SECRET_NOT_EXPOSED");
    }

    private ReviewTask submit(ReviewService service, String skillId, String version, String sha) {
        return service.submitValidatedPackage(
                new PackageValidationResult(true, skillId, version, sha, 42, List.of()),
                new StoredPackage("package-" + skillId, "C:/packages/" + skillId + ".zip"),
                new Actor("owner", "maintainer"), "submit-" + skillId);
    }

    private Path tempState(String name) throws Exception {
        return Files.createTempDirectory("governance-release-" + name + "-").resolve("state.json");
    }
}
