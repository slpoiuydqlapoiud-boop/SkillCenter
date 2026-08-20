package com.huawei.skillcenter.distribution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InstallManifest(
        String schemaVersion,
        UUID manifestId,
        SkillArtifact skill,
        Artifact artifact,
        List<Compatibility> compatibility,
        Permissions permissions,
        List<Dependency> dependencies,
        Instant issuedAt,
        Instant expiresAt,
        LifecycleNotice lifecycle
) {
    public InstallManifest(String schemaVersion, UUID manifestId, SkillArtifact skill, Artifact artifact,
                           List<Compatibility> compatibility, Permissions permissions, List<Dependency> dependencies,
                           Instant issuedAt, Instant expiresAt) {
        this(schemaVersion, manifestId, skill, artifact, compatibility, permissions, dependencies, issuedAt, expiresAt, null);
    }

    public record SkillArtifact(String id, String name, String version, String status) {}

    public record Artifact(String downloadUrl, String sha256, long sizeBytes, String mediaType) {}

    public record Compatibility(String client, String minimumVersion, String maximumVersion) {}

    public record Permissions(String summary, String workspace, String network, String shell, String credentials) {}

    public record Dependency(String type, String name, String version) {}

    public record LifecycleNotice(String status, String reason, String replacementVersion) {}
}
