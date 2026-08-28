package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillVisibilityContext;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.release.ReleaseAdmissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ArtifactDownloadService {
    private final GovernanceStore store;
    private final DistributionAuthorizationService authorizationService;
    private final ArtifactPackageService artifactPackageService;
    private final ReleaseAdmissionService releaseAdmissionService;
    private final SkillAuthorizationService skillAuthorizationService;
    private final ArtifactStorage artifactStorage;

    public ArtifactDownloadService(GovernanceStore store, DistributionAuthorizationService authorizationService,
                                   ArtifactPackageService artifactPackageService,
                                   ReleaseAdmissionService releaseAdmissionService) {
        this(store, authorizationService, artifactPackageService, releaseAdmissionService, null, null);
    }

    public ArtifactDownloadService(GovernanceStore store, DistributionAuthorizationService authorizationService,
                                   ArtifactPackageService artifactPackageService,
                                   ReleaseAdmissionService releaseAdmissionService,
                                   SkillAuthorizationService skillAuthorizationService) {
        this(store, authorizationService, artifactPackageService, releaseAdmissionService, skillAuthorizationService, null);
    }

    @Autowired
    public ArtifactDownloadService(GovernanceStore store, DistributionAuthorizationService authorizationService,
                                   ArtifactPackageService artifactPackageService,
                                   ReleaseAdmissionService releaseAdmissionService,
                                   SkillAuthorizationService skillAuthorizationService,
                                   ArtifactStorage artifactStorage) {
        this.store = store;
        this.authorizationService = authorizationService;
        this.artifactPackageService = artifactPackageService;
        this.releaseAdmissionService = releaseAdmissionService;
        this.skillAuthorizationService = skillAuthorizationService;
        this.artifactStorage = artifactStorage;
    }

    public DownloadedArtifact download(String skillId, String version, String token) {
        return download(skillId, version, token, null);
    }

    public DownloadedArtifact download(String skillId, String version, String token, Actor actor) {
        if (skillAuthorizationService != null && actor != null) {
            skillAuthorizationService.requireVisible(skillId, actor, SkillVisibilityContext.DISTRIBUTION);
        }
        DistributionAuthorization authorization = authorizationService.lookup(token);
        if (!skillId.equals(authorization.skillId()) || !version.equals(authorization.version())) {
            throw new DistributionAuthorizationException("authorization does not match the requested artifact");
        }
        SkillVersion skillVersion = store.snapshot().versions().stream()
                .filter(candidate -> candidate.skillId().equals(skillId))
                .filter(candidate -> candidate.version().equals(version))
                .filter(candidate -> Set.of("published", "deprecated")
                        .contains(candidate.status().toLowerCase(java.util.Locale.ROOT)))
                .max(java.util.Comparator.comparing(SkillVersion::publishedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .orElseThrow(() -> new ArtifactNotFoundException("published artifact was not found"));
        if ("withdrawn".equalsIgnoreCase(skillVersion.status())) {
            throw new WithdrawnVersionUnavailableException("Version has been withdrawn");
        }
        if (!Set.of("published", "deprecated").contains(skillVersion.status().toLowerCase(java.util.Locale.ROOT))) {
            throw new ArtifactNotFoundException("published artifact was not found");
        }
        releaseAdmissionService.requireDownloadable(skillId, version);
        if (skillVersion.artifactPath() == null || skillVersion.artifactPath().isBlank()) {
            ArtifactPackageService.GeneratedArtifact generated = artifactPackageService.generate(skillId, version);
            DistributionAuthorization consumed = authorizationService.consume(token);
            Instant now = Instant.now();
            store.addAudit(new AuditEvent(UUID.randomUUID().toString(), "ARTIFACT_DOWNLOADED", "DISTRIBUTION_AUTHORIZATION",
                    consumed.tokenId(), consumed.requestedBy(), "viewer", "distribution", now,
                    Map.of("skillId", skillId, "version", version, "method", consumed.method())));
            return new DownloadedArtifact(generated.resource(), generated.sha256(), generated.sizeBytes());
        }
        ArtifactStorage.ArtifactResource artifact;
        if (artifactStorage != null) {
            artifact = artifactStorage.open(skillVersion.artifactPath(), skillVersion.sha256());
        } else {
            Path artifactPath = Path.of(skillVersion.artifactPath()).toAbsolutePath().normalize();
            ArtifactIntegrityVerifier.VerifiedArtifact verified = ArtifactIntegrityVerifier.verify(
                    artifactPath, skillVersion.sha256());
            artifact = new ArtifactStorage.ArtifactResource(new FileSystemResource(artifactPath),
                    verified.sha256(), verified.sizeBytes());
        }
        DistributionAuthorization consumed = authorizationService.consume(token);
        Instant now = Instant.now();
        store.addAudit(new AuditEvent(UUID.randomUUID().toString(), "ARTIFACT_DOWNLOADED", "DISTRIBUTION_AUTHORIZATION",
                consumed.tokenId(), consumed.requestedBy(), "viewer", "distribution", now,
                Map.of("skillId", skillId, "version", version, "method", consumed.method())));
        return new DownloadedArtifact(artifact.resource(), artifact.sha256(), artifact.sizeBytes());
    }

    public record DownloadedArtifact(Resource resource, String sha256, long sizeBytes) {
    }
}
