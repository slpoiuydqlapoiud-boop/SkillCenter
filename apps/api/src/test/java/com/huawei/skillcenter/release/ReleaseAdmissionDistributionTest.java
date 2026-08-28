package com.huawei.skillcenter.release;

import com.huawei.skillcenter.distribution.ArtifactDownloadService;
import com.huawei.skillcenter.distribution.ArtifactPackageService;
import com.huawei.skillcenter.distribution.DistributionAuthorization;
import com.huawei.skillcenter.distribution.DistributionAuthorizationService;
import com.huawei.skillcenter.distribution.DistributionService;
import com.huawei.skillcenter.distribution.InstallationRequest;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillRecord;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseAdmissionDistributionTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @Test
    void manifestChecksAdmissionBeforePreparingArtifact() {
        GovernanceStore governance = mock(GovernanceStore.class);
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        ArtifactPackageService artifacts = mock(ArtifactPackageService.class);
        ReleaseAdmissionService admission = mock(ReleaseAdmissionService.class);
        SkillVersion version = version();
        SkillRecord skill = skill();
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(version), List.of(), List.of(), List.of()));
        when(catalog.detail("skill-a")).thenReturn(skill);
        when(admission.requireDownloadable("skill-a", "1.0.0"))
                .thenThrow(new ReleaseAdmissionException("RELEASE_NOT_PROMOTED", "Release is not promoted"));

        DistributionService service = new DistributionService(catalog, governance, artifacts, admission,
                "https://skill-center.internal/artifacts");

        assertThatThrownBy(() -> service.createManifest("skill-a", new InstallationRequest("codex", "1.0.0", "cli")))
                .isInstanceOf(ReleaseAdmissionException.class)
                .extracting("reasonCode").isEqualTo("RELEASE_NOT_PROMOTED");
        verify(admission).requireDownloadable("skill-a", "1.0.0");
        verifyNoInteractions(artifacts);
    }

    @Test
    void downloadChecksAdmissionBeforeConsumingAuthorization() {
        GovernanceStore governance = mock(GovernanceStore.class);
        DistributionAuthorizationService authorizations = mock(DistributionAuthorizationService.class);
        ArtifactPackageService artifacts = mock(ArtifactPackageService.class);
        ReleaseAdmissionService admission = mock(ReleaseAdmissionService.class);
        when(governance.snapshot()).thenReturn(new GovernanceSnapshot(List.of(version()), List.of(), List.of(), List.of()));
        when(authorizations.lookup("token")).thenReturn(new DistributionAuthorization(
                "token-id", "digest", "skill-a", "1.0.0", "installation", "viewer", "codex", "1.0.0",
                "cli", NOW, NOW.plusSeconds(900), null, null, null));
        when(admission.requireDownloadable("skill-a", "1.0.0"))
                .thenThrow(new ReleaseAdmissionException("RELEASE_NOT_PROMOTED", "Release is not promoted"));

        ArtifactDownloadService service = new ArtifactDownloadService(governance, authorizations, artifacts, admission);

        assertThatThrownBy(() -> service.download("skill-a", "1.0.0", "token"))
                .isInstanceOf(ReleaseAdmissionException.class)
                .extracting("reasonCode").isEqualTo("RELEASE_NOT_PROMOTED");
        verify(authorizations, never()).consume("token");
        verifyNoInteractions(artifacts);
    }

    private SkillVersion version() {
        return new SkillVersion("package-1", "skill-a", "1.0.0", "published", "a".repeat(64),
                10, "", "owner", NOW, "owner", NOW, "review-1");
    }

    private SkillRecord skill() {
        SkillRecord skill = mock(SkillRecord.class);
        when(skill.id()).thenReturn("skill-a");
        when(skill.name()).thenReturn("Skill A");
        when(skill.version()).thenReturn("1.0.0");
        when(skill.status()).thenReturn("published");
        when(skill.permissionSummary()).thenReturn("read");
        return skill;
    }
}
