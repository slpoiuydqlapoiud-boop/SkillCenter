package com.huawei.skillcenter.persistence;

import java.time.Instant;

public record PersistenceArtifactStatus(
        String artifactId,
        PersistenceArtifactState state,
        int schemaVersion,
        Integer observedVersion,
        Long sizeBytes,
        String sha256,
        Long recordCount,
        Instant checkedAt,
        String stableReasonCode) {

    public PersistenceArtifactStatus {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
        artifactId = artifactId.trim();
        stableReasonCode = stableReasonCode == null ? "" : stableReasonCode.trim();
    }
}
