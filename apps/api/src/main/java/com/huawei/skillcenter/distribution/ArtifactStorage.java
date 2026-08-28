package com.huawei.skillcenter.distribution;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Storage boundary for immutable Skill package artifacts.
 *
 * Callers intentionally work with an opaque reference. Implementations own
 * path/key resolution and must not expose storage roots through this port.
 */
public interface ArtifactStorage {
    StoredArtifact store(Path source, String packageId) throws IOException;

    ArtifactMetadata inspect(String reference, String expectedSha256);

    ArtifactResource open(String reference, String expectedSha256);

    record StoredArtifact(String packageId, String reference, String sha256, long sizeBytes) {
    }

    record ArtifactMetadata(String sha256, long sizeBytes) {
    }

    record ArtifactResource(Resource resource, String sha256, long sizeBytes) {
    }
}
