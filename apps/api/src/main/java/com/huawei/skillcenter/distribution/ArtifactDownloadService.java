package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ArtifactDownloadService {
    private final GovernanceStore store;
    private final DistributionAuthorizationService authorizationService;
    private final ArtifactPackageService artifactPackageService;

    public ArtifactDownloadService(GovernanceStore store, DistributionAuthorizationService authorizationService,
                                   ArtifactPackageService artifactPackageService) {
        this.store = store;
        this.authorizationService = authorizationService;
        this.artifactPackageService = artifactPackageService;
    }

    public DownloadedArtifact download(String skillId, String version, String token) {
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
        if (skillVersion.artifactPath() == null || skillVersion.artifactPath().isBlank()) {
            ArtifactPackageService.GeneratedArtifact generated = artifactPackageService.generate(skillId, version);
            DistributionAuthorization consumed = authorizationService.consume(token);
            Instant now = Instant.now();
            store.addAudit(new AuditEvent(UUID.randomUUID().toString(), "ARTIFACT_DOWNLOADED", "DISTRIBUTION_AUTHORIZATION",
                    consumed.tokenId(), consumed.requestedBy(), "viewer", "distribution", now,
                    Map.of("skillId", skillId, "version", version, "method", consumed.method())));
            return new DownloadedArtifact(generated.resource(), generated.sha256(), generated.sizeBytes());
        }
        Path artifactPath = Path.of(skillVersion.artifactPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifactPath) || !artifactPath.getFileName().toString().toLowerCase().endsWith(".zip")) {
            throw new ArtifactNotFoundException("published artifact was not found");
        }
        long sizeBytes;
        try {
            sizeBytes = Files.size(artifactPath);
        } catch (IOException exception) {
            throw new ArtifactNotFoundException("published artifact was not found");
        }
        DistributionAuthorization consumed = authorizationService.consume(token);
        Instant now = Instant.now();
        store.addAudit(new AuditEvent(UUID.randomUUID().toString(), "ARTIFACT_DOWNLOADED", "DISTRIBUTION_AUTHORIZATION",
                consumed.tokenId(), consumed.requestedBy(), "viewer", "distribution", now,
                Map.of("skillId", skillId, "version", version, "method", consumed.method())));
        return new DownloadedArtifact(new FileSystemResource(artifactPath), skillVersion.sha256(), sizeBytes);
    }

    public record DownloadedArtifact(Resource resource, String sha256, long sizeBytes) {
    }
}
