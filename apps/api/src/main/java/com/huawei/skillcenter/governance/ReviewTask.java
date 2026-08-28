package com.huawei.skillcenter.governance;

import java.time.Instant;

public record ReviewTask(
        String reviewId,
        String packageId,
        String skillId,
        String version,
        String status,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String reason,
        String riskLevel,
        String securityReviewedBy,
        Instant securityReviewedAt,
        String securityReason,
        SecurityScanEvidence securityEvidence
) {
    public ReviewTask(String reviewId,
                      String packageId,
                      String skillId,
                      String version,
                      String status,
                      String submittedBy,
                      Instant submittedAt,
                      String reviewedBy,
                      Instant reviewedAt,
                      String reason) {
        this(reviewId, packageId, skillId, version, status, submittedBy, submittedAt, reviewedBy, reviewedAt,
                reason, "low", null, null, null, SecurityScanEvidence.legacy());
    }

    public ReviewTask(String reviewId,
                      String packageId,
                      String skillId,
                      String version,
                      String status,
                      String submittedBy,
                      Instant submittedAt,
                      String reviewedBy,
                      Instant reviewedAt,
                      String reason,
                      String riskLevel,
                      String securityReviewedBy,
                      Instant securityReviewedAt,
                      String securityReason) {
        this(reviewId, packageId, skillId, version, status, submittedBy, submittedAt, reviewedBy, reviewedAt,
                reason, riskLevel, securityReviewedBy, securityReviewedAt, securityReason,
                SecurityScanEvidence.legacy());
    }

    public ReviewTask {
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel.trim().toLowerCase(java.util.Locale.ROOT);
        securityEvidence = securityEvidence == null ? SecurityScanEvidence.legacy() : securityEvidence;
    }
}
