package com.huawei.skillcenter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReleaseAdmissionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");
    private static final String SKILL_ID = "skill-a";
    private static final String VERSION = "1.0.0";
    private static final String SHA = "a".repeat(64);

    @TempDir
    Path tempDir;

    private GovernanceStore governance;
    private ReleaseRecordStore releases;
    private SkillVersion version;

    @BeforeEach
    void setUp() {
        governance = mock(GovernanceStore.class);
        version = new SkillVersion("package-1", SKILL_ID, VERSION, "published", SHA,
                10, "", "owner", NOW, "owner", NOW, "review-1");
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(version),
                List.of(), List.of(), List.of()));
        releases = new ReleaseRecordStore(tempDir.resolve("releases.json"),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void keepsHistoricalVersionDownloadableWhenNoProductionReleaseExists() {
        ReleaseAdmissionDecision decision = service(ReleaseAdmissionMode.LEGACY_COMPATIBLE)
                .requireDownloadable(SKILL_ID, VERSION);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.legacyCompatible()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("LEGACY_COMPATIBLE");
    }

    @Test
    void controlledModeFailsClosedWhenProductionReleaseIsMissing() {
        assertThatThrownBy(() -> service(ReleaseAdmissionMode.CONTROLLED)
                .requireDownloadable(SKILL_ID, VERSION))
                .isInstanceOf(ReleaseAdmissionException.class)
                .hasMessage("Release admission is required")
                .extracting("reasonCode")
                .isEqualTo("RELEASE_ADMISSION_REQUIRED");
    }

    @Test
    void productionReleaseMustBePromoted() {
        releases.create(request(ReleaseEnvironment.PRODUCTION, SHA, "prod-request"));

        assertThatThrownBy(() -> service(ReleaseAdmissionMode.LEGACY_COMPATIBLE)
                .requireDownloadable(SKILL_ID, VERSION))
                .isInstanceOf(ReleaseAdmissionException.class)
                .extracting("reasonCode")
                .isEqualTo("RELEASE_NOT_PROMOTED");
    }

    @Test
    void productionPromotedReleaseMustMatchArtifactHash() {
        ReleaseRecord promoted = request(ReleaseEnvironment.PRODUCTION, "b".repeat(64), "prod-mismatch")
                .approve("reviewer", NOW.plusSeconds(1))
                .promoting("mock/prod-mismatch", NOW.plusSeconds(2))
                .promoted("mock/prod-mismatch", NOW.plusSeconds(3));
        releases.create(promoted);

        assertThatThrownBy(() -> service(ReleaseAdmissionMode.CONTROLLED)
                .requireDownloadable(SKILL_ID, VERSION))
                .isInstanceOf(ReleaseAdmissionException.class)
                .extracting("reasonCode")
                .isEqualTo("RELEASE_ARTIFACT_MISMATCH");
    }

    @Test
    void matchingPromotedProductionReleaseIsAdmitted() {
        ReleaseRecord promoted = request(ReleaseEnvironment.PRODUCTION, SHA, "prod-ok")
                .approve("reviewer", NOW.plusSeconds(1))
                .promoting("mock/prod-ok", NOW.plusSeconds(2))
                .promoted("mock/prod-ok", NOW.plusSeconds(3));
        releases.create(promoted);

        ReleaseAdmissionDecision decision = service(ReleaseAdmissionMode.CONTROLLED)
                .requireDownloadable(SKILL_ID, VERSION);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.legacyCompatible()).isFalse();
        assertThat(decision.releaseId()).isEqualTo("prod-ok");
        assertThat(decision.reasonCode()).isEqualTo("RELEASE_PROMOTED");
    }

    private ReleaseAdmissionService service(ReleaseAdmissionMode mode) {
        return new ReleaseAdmissionService(governance, releases, mode);
    }

    private ReleaseRecord request(ReleaseEnvironment environment, String sha, String releaseId) {
        return ReleaseRecord.request(releaseId, SKILL_ID, VERSION, sha, environment,
                ReleaseGateSnapshot.passed(NOW), "idem-" + releaseId, "owner", NOW);
    }
}
