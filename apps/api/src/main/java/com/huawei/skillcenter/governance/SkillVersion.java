package com.huawei.skillcenter.governance;

import java.time.Instant;

public record SkillVersion(
        String packageId,
        String skillId,
        String version,
        String status,
        String sha256,
        long sizeBytes,
        String artifactPath,
        String uploadedBy,
        Instant uploadedAt,
        String publishedBy,
        Instant publishedAt,
        String reviewId,
        String statusReason,
        String replacementVersion,
        String statusChangedBy,
        Instant statusChangedAt
) {
    public SkillVersion(String packageId,
                        String skillId,
                        String version,
                        String status,
                        String sha256,
                        long sizeBytes,
                        String artifactPath,
                        String uploadedBy,
                        Instant uploadedAt,
                        String publishedBy,
                        Instant publishedAt,
                        String reviewId) {
        this(packageId, skillId, version, status, sha256, sizeBytes, artifactPath, uploadedBy, uploadedAt,
                publishedBy, publishedAt, reviewId, null, null, null, null);
    }
}
