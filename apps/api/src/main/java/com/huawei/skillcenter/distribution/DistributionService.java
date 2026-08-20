package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.skill.SkillCatalogService;
import com.huawei.skillcenter.skill.SkillRecord;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DistributionService {
    private final SkillCatalogService catalogService;
    private final GovernanceStore governanceStore;
    private final ArtifactPackageService artifactPackageService;
    private final String artifactBaseUrl;

    public DistributionService(SkillCatalogService catalogService,
                               GovernanceStore governanceStore,
                               ArtifactPackageService artifactPackageService,
                               @Value("${skill-center.artifact-base-url:https://skill-center.internal/artifacts}") String artifactBaseUrl) {
        this.catalogService = catalogService;
        this.governanceStore = governanceStore;
        this.artifactPackageService = artifactPackageService;
        this.artifactBaseUrl = artifactBaseUrl.replaceAll("/$", "");
    }

    public InstallManifest createManifest(String skillId, InstallationRequest request) {
        SkillVersion governedVersion = governanceStore.snapshot().versions().stream()
                .filter(candidate -> skillId.equals(candidate.skillId()))
                .filter(candidate -> java.util.Set.of("published", "deprecated", "withdrawn")
                        .contains(candidate.status().toLowerCase(java.util.Locale.ROOT)))
                .max(java.util.Comparator.comparing(SkillVersion::publishedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .orElse(null);
        if (governedVersion != null && "withdrawn".equalsIgnoreCase(governedVersion.status())) {
            throw new WithdrawnVersionUnavailableException("Version has been withdrawn");
        }
        SkillRecord skill = catalogService.detail(skillId);
        Instant issuedAt = Instant.now();
        ArtifactPackageService.ArtifactMetadata artifactMetadata = artifactPackageService.metadata(skill.id(), skill.version(), governedVersion);
        return new InstallManifest(
                "1.0",
                UUID.randomUUID(),
                new InstallManifest.SkillArtifact(skill.id(), skill.name(), skill.version(), skill.status()),
                new InstallManifest.Artifact(
                        artifactBaseUrl + "/" + skill.id() + "/" + skill.version() + ".zip",
                        artifactMetadata.sha256(),
                        artifactMetadata.sizeBytes(),
                        "application/zip"),
                List.of(new InstallManifest.Compatibility(
                        request == null || request.clientType() == null || request.clientType().isBlank()
                                ? "codex" : request.clientType(),
                        "1.0.0", null)),
                new InstallManifest.Permissions(skill.permissionSummary(), "read", "internal", "restricted", "none"),
                List.of(),
                issuedAt,
                issuedAt.plus(Duration.ofHours(24)),
                governedVersion == null || "published".equalsIgnoreCase(governedVersion.status())
                        ? null
                        : new InstallManifest.LifecycleNotice(governedVersion.status(), governedVersion.statusReason(),
                        governedVersion.replacementVersion()));
    }

}
