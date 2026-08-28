package com.huawei.skillcenter.persistence;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record PersistenceSnapshotManifest(
        String snapshotId,
        Instant createdAt,
        String backend,
        int artifactCount,
        String manifestSha256,
        String state,
        List<PersistenceSnapshotArtifact> artifacts) {

    public PersistenceSnapshotManifest {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        if (artifactCount < 0) {
            throw new IllegalArgumentException("artifactCount must not be negative");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }
        Objects.requireNonNull(artifacts, "artifacts must not be null");
        snapshotId = snapshotId.trim();
        backend = backend.trim();
        manifestSha256 = manifestSha256 == null ? "" : manifestSha256.trim();
        state = state.trim();
        List<PersistenceSnapshotArtifact> sortedArtifacts = artifacts.stream()
                .sorted(Comparator.comparing(PersistenceSnapshotArtifact::artifactId))
                .toList();
        if (artifactCount != sortedArtifacts.size()) {
            throw new IllegalArgumentException("artifactCount must match artifacts size");
        }
        artifacts = List.copyOf(sortedArtifacts);
    }
}
