package com.huawei.skillcenter.release;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.QualityGateBlockedException;
import com.huawei.skillcenter.governance.QualityReleaseGate;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.operations.PlatformReadiness;
import com.huawei.skillcenter.operations.ProductionReadinessGate;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @TempDir
    Path tempDir;

    private GovernanceStore governance;
    private ReleaseRecordStore releases;
    private QualityReleaseGate gate;
    private ReleaseTarget target;
    private OptimizationExperimentAssessmentStore assessments;
    private ProductionReadinessGate productionReadinessGate;

    @BeforeEach
    void setUp() {
        governance = mock(GovernanceStore.class);
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(
                new SkillVersion("package-1", "skill-a", "1.0.0", "published", "a".repeat(64),
                        10, "", "owner", NOW, "owner", NOW, "review-1")),
                List.of(), List.of(), List.of()));
        releases = new ReleaseRecordStore(tempDir.resolve("releases.json"), new ObjectMapper().findAndRegisterModules());
        gate = mock(QualityReleaseGate.class);
        target = mock(ReleaseTarget.class);
        assessments = mock(OptimizationExperimentAssessmentStore.class);
        productionReadinessGate = mock(ProductionReadinessGate.class);
    }

    @Test
    void createsStagingRequestAndReusesSameIdempotencyKey() {
        when(gate.evaluate("skill-a", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));
        ReleaseService service = service();
        ReleaseRequest request = new ReleaseRequest("skill-a", "1.0.0", ReleaseEnvironment.STAGING, "", "idem-1");

        ReleaseRecord first = service.request(request, new Actor("owner", "maintainer"), "req-1");
        ReleaseRecord replay = service.request(request, new Actor("owner", "maintainer"), "req-2");

        assertThat(first.status()).isEqualTo(ReleaseStatus.REQUESTED);
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void approvedVersionEnrollmentIsIdempotentAndUsesFrozenSnapshot() {
        ReleaseGateSnapshot snapshot = new ReleaseGateSnapshot(NOW, "PASSED", List.of("QUALITY_SNAPSHOT_USED"),
                "quality-1", "", "", "", "production", "", "", "runtime-1", "mcp-1", "llm-1");
        SkillVersion version = governance.snapshot().versions().get(0);
        ReleaseService service = service();

        ReleaseRecord first = service.requestFromApprovedVersion(version, snapshot,
                new Actor("reviewer", "reviewer"), "review:review-1:staging");
        ReleaseRecord replay = service.requestFromApprovedVersion(version, snapshot,
                new Actor("different-reviewer", "reviewer"), "review:review-1:staging");

        assertThat(replay).isEqualTo(first);
        assertThat(releases.findAll("skill-a", "1.0.0", ReleaseEnvironment.STAGING, null)).hasSize(1);
        verifyNoInteractions(gate);
    }

    @Test
    void blocksQualityGateAndProductionWithoutEvidence() {
        when(gate.evaluate("skill-a", "1.0.0"))
                .thenReturn(new ReleaseGateSnapshot(NOW, "BLOCKED", List.of("QUALITY_SNAPSHOT_BLOCKED"),
                        "", "", "", "", "all", "", "", "", "", ""));
        ReleaseService service = service();

        assertThatThrownBy(() -> service.request(
                new ReleaseRequest("skill-a", "1.0.0", ReleaseEnvironment.STAGING, "", "idem-1"),
                new Actor("owner", "maintainer"), "req-1"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("QUALITY_SNAPSHOT_BLOCKED");

        when(gate.evaluate("skill-a", "1.0.0"))
                .thenReturn(new ReleaseGateSnapshot(NOW, "NO_EVIDENCE", List.of(),
                        "", "", "", "", "all", "", "", "", "", ""));
        assertThatThrownBy(() -> service.request(
                new ReleaseRequest("skill-a", "1.0.0", ReleaseEnvironment.PRODUCTION, "", "idem-2"),
                new Actor("owner", "maintainer"), "req-2"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("QUALITY_EVIDENCE_REQUIRED");
    }

    @Test
    void blocksProductionBeforeCreatingRecordOrCallingReleaseTargetWhenPlatformIsNotReady() {
        when(gate.evaluate("skill-a", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));
        when(productionReadinessGate.readiness()).thenReturn(new PlatformReadiness(
                "NOT_READY", "PRODUCTION_HANDOFF", NOW,
                List.of(new PlatformReadiness.Component(
                        "PRODUCTION_EXTERNAL_EVIDENCE", "NOT_READY",
                        "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED", "外部证据尚未核验")),
                List.of("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED")));
        ReleaseService service = serviceWithProductionGate();

        assertThatThrownBy(() -> service.request(
                new ReleaseRequest("skill-a", "1.0.0", ReleaseEnvironment.PRODUCTION, "", "prod-blocked"),
                new Actor("owner", "maintainer"), "req-prod-blocked"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED");

        assertThat(releases.findAll("skill-a", "1.0.0", ReleaseEnvironment.PRODUCTION, null)).isEmpty();
        verifyNoInteractions(target);
    }

    @Test
    void failsClosedWhenProductionReadinessGateIsNotWired() {
        when(gate.evaluate("skill-a", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));

        assertThatThrownBy(() -> service().request(
                new ReleaseRequest("skill-a", "1.0.0", ReleaseEnvironment.PRODUCTION, "", "prod-no-gate"),
                new Actor("owner", "maintainer"), "req-prod-no-gate"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("PLATFORM_PRODUCTION_READINESS_UNAVAILABLE");
    }

    @Test
    void blocksProductionPromotionBeforeChangingStatusOrCallingReleaseTargetWhenPlatformIsNotReady() {
        when(productionReadinessGate.readiness()).thenReturn(new PlatformReadiness(
                "NOT_READY", "PRODUCTION_HANDOFF", NOW,
                List.of(new PlatformReadiness.Component(
                        "PRODUCTION_EXTERNAL_EVIDENCE", "NOT_READY",
                        "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED", "外部证据尚未核验")),
                List.of("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED")));
        ReleaseRecord approved = ReleaseRecord.request(
                        "release-prod-1", "skill-a", "1.0.0", "a".repeat(64),
                        ReleaseEnvironment.PRODUCTION, ReleaseGateSnapshot.passed(NOW),
                        "prod-approved", "owner", NOW)
                .approve("reviewer", NOW.plusSeconds(1));
        releases.create(approved);

        assertThatThrownBy(() -> serviceWithProductionGate().promote(
                approved.releaseId(), new Actor("admin", "admin"), "req-prod-promote"))
                .isInstanceOf(QualityGateBlockedException.class)
                .hasMessageContaining("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED");

        assertThat(releases.find(approved.releaseId()).orElseThrow().status())
                .isEqualTo(ReleaseStatus.APPROVED);
        verifyNoInteractions(target);
    }

    @Test
    void reviewerCanApproveStagingButCannotApproveProductionOrOwnRequest() {
        when(gate.evaluate("skill-a", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));
        ReleaseService service = service();
        ReleaseRecord staging = service.request(
                new ReleaseRequest("skill-a", "1.0.0", ReleaseEnvironment.STAGING, "", "idem-1"),
                new Actor("owner", "maintainer"), "req-1");

        assertThatThrownBy(() -> service.approve(staging.releaseId(), new Actor("owner", "reviewer"), "req-2"))
                .isInstanceOf(ReleaseInvalidStateException.class);
        assertThat(service.approve(staging.releaseId(), new Actor("reviewer", "reviewer"), "req-3").status())
                .isEqualTo(ReleaseStatus.APPROVED);
    }

    @Test
    void targetFailureIsPersistedAsFailedAndRestartMarksInflightUnknown() {
        when(gate.evaluate("skill-a", "1.0.0")).thenReturn(ReleaseGateSnapshot.passed(NOW));
        when(target.promote(org.mockito.ArgumentMatchers.any())).thenReturn(ReleaseTargetResult.failure("TARGET_FAILED"));
        ReleaseService service = service();
        ReleaseRecord requested = service.request(
                new ReleaseRequest("skill-a", "1.0.0", ReleaseEnvironment.STAGING, "", "idem-1"),
                new Actor("owner", "maintainer"), "req-1");
        service.approve(requested.releaseId(), new Actor("reviewer", "reviewer"), "req-2");

        assertThatThrownBy(() -> service.promote(requested.releaseId(), new Actor("admin", "admin"), "req-3"))
                .isInstanceOf(ReleaseTargetException.class);
        assertThat(service.find(requested.releaseId(), new Actor("admin", "admin")).status())
                .isEqualTo(ReleaseStatus.FAILED);

        ReleaseRecord inflight = ReleaseRecord.request("release-2", "skill-a", "1.0.0", "b".repeat(64),
                        ReleaseEnvironment.STAGING, ReleaseGateSnapshot.passed(NOW), "idem-2", "owner", NOW)
                .approve("reviewer", NOW.plusSeconds(1)).promoting("mock/release-2", NOW.plusSeconds(2));
        releases.create(inflight);
        new ReleaseService(governance, gate, releases, target, assessments,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        assertThat(releases.find("release-2").orElseThrow().status()).isEqualTo(ReleaseStatus.FAILED);
    }

    private ReleaseService service() {
        return new ReleaseService(governance, gate, releases, target, assessments,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ReleaseService serviceWithProductionGate() {
        return new ReleaseService(governance, gate, releases, target, assessments,
                null, productionReadinessGate, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
