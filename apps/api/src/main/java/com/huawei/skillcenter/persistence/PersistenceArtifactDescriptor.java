package com.huawei.skillcenter.persistence;

import java.nio.file.Path;
import java.util.Objects;

public record PersistenceArtifactDescriptor(
        String artifactId,
        PersistenceArtifactKind kind,
        int schemaVersion,
        Path storagePath,
        boolean critical,
        boolean includeInSnapshot,
        String physicalBackend) {

    public PersistenceArtifactDescriptor(String artifactId,
                                         PersistenceArtifactKind kind,
                                         int schemaVersion,
                                         Path storagePath,
                                         boolean critical,
                                         boolean includeInSnapshot) {
        this(artifactId, kind, schemaVersion, storagePath, critical, includeInSnapshot, "json");
    }

    public PersistenceArtifactDescriptor {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        Objects.requireNonNull(kind, "kind must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
        Objects.requireNonNull(storagePath, "storagePath must not be null");
        Path normalized = storagePath.toAbsolutePath().normalize();
        if (!normalized.isAbsolute()) {
            throw new IllegalArgumentException("storagePath must be absolute");
        }
        artifactId = artifactId.trim();
        storagePath = normalized;
        physicalBackend = PersistenceControlProperties.normalizeBackendValue(physicalBackend);
        if (!"json".equals(physicalBackend) && !"postgresql".equals(physicalBackend)) {
            throw new IllegalArgumentException("physicalBackend must be json or postgresql");
        }
    }
}
