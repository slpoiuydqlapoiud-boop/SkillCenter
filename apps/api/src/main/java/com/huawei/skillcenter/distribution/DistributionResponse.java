package com.huawei.skillcenter.distribution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DistributionResponse(
        String schemaVersion,
        UUID manifestId,
        InstallManifest.SkillArtifact skill,
        InstallManifest.Artifact artifact,
        List<InstallManifest.Compatibility> compatibility,
        InstallManifest.Permissions permissions,
        List<InstallManifest.Dependency> dependencies,
        Instant issuedAt,
        Instant expiresAt,
        InstallManifest manifest,
        String installationId,
        Authorization authorization,
        String cliCommand
) {
    public record Authorization(
            String tokenId,
            String token,
            Instant expiresAt,
            String method,
            String downloadUrl,
            Instant consumedAt
    ) {
    }

    public static DistributionResponse from(InstallManifest manifest,
                                            String installationId,
                                            Authorization authorization,
                                            String cliCommand) {
        return new DistributionResponse(manifest.schemaVersion(), manifest.manifestId(), manifest.skill(),
                manifest.artifact(), manifest.compatibility(), manifest.permissions(), manifest.dependencies(),
                manifest.issuedAt(), manifest.expiresAt(), manifest, installationId, authorization, cliCommand);
    }
}
