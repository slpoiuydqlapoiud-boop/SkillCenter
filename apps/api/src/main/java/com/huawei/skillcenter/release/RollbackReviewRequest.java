package com.huawei.skillcenter.release;

public record RollbackReviewRequest(String reason, String assessmentId,
                                    String targetVersion, String targetReleaseId) {
    public RollbackReviewRequest {
        if (reason == null || reason.isBlank() || reason.trim().length() > 512) {
            throw new IllegalArgumentException("reason must be between 1 and 512 characters");
        }
        reason = reason.trim();
        assessmentId = optional(assessmentId, "assessmentId");
        targetVersion = optional(targetVersion, "targetVersion");
        targetReleaseId = optional(targetReleaseId, "targetReleaseId");
        if (targetVersion.isBlank() && targetReleaseId.isBlank()) {
            throw new IllegalArgumentException("targetVersion or targetReleaseId is required");
        }
    }

    private static String optional(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && (normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
