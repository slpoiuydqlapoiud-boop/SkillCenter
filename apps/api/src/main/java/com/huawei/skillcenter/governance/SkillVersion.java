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
        Instant statusChangedAt,
        String riskLevel,
        SecurityScanEvidence securityEvidence
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
                publishedBy, publishedAt, reviewId, null, null, null, null, "low", SecurityScanEvidence.legacy());
    }

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
                        String reviewId,
                        String statusReason,
                        String replacementVersion,
                        String statusChangedBy,
                        Instant statusChangedAt) {
        this(packageId, skillId, version, status, sha256, sizeBytes, artifactPath, uploadedBy, uploadedAt,
                publishedBy, publishedAt, reviewId, statusReason, replacementVersion, statusChangedBy,
                statusChangedAt, "low", SecurityScanEvidence.legacy());
    }

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
                        String reviewId,
                        String statusReason,
                        String replacementVersion,
                        String statusChangedBy,
                        Instant statusChangedAt,
                        String riskLevel) {
        this(packageId, skillId, version, status, sha256, sizeBytes, artifactPath, uploadedBy, uploadedAt,
                publishedBy, publishedAt, reviewId, statusReason, replacementVersion, statusChangedBy,
                statusChangedAt, riskLevel, SecurityScanEvidence.legacy());
    }

    public SkillVersion {
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel.trim().toLowerCase(java.util.Locale.ROOT);
        securityEvidence = securityEvidence == null ? SecurityScanEvidence.legacy() : securityEvidence;
    }
}
