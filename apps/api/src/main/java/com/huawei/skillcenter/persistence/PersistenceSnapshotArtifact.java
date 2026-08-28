package com.huawei.skillcenter.persistence;

import java.util.Objects;

public record PersistenceSnapshotArtifact(
        String artifactId,
        PersistenceArtifactKind kind,
        int schemaVersion,
        String availability,
        String relativePath,
        Long sizeBytes,
        String sha256,
        Long recordCount) {

    public static final String READY = "READY";
    public static final String OPTIONAL_MISSING = "OPTIONAL_MISSING";

    public PersistenceSnapshotArtifact {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        Objects.requireNonNull(kind, "kind must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
        if (!READY.equals(availability) && !OPTIONAL_MISSING.equals(availability)) {
            throw new IllegalArgumentException("availability must be READY or OPTIONAL_MISSING");
        }
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        if (recordCount != null && recordCount < 0) {
            throw new IllegalArgumentException("recordCount must not be negative");
        }
        artifactId = artifactId.trim();
        availability = availability.trim();
        relativePath = relativePath == null ? "" : relativePath.trim();
        sha256 = sha256 == null ? null : sha256.trim();
    }
}
