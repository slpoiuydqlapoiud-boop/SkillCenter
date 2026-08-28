package com.huawei.skillcenter.release;

public record ReleaseRequest(String skillId, String version, ReleaseEnvironment targetEnvironment,
                             String sourceAssessmentId, String idempotencyKey) {
    public ReleaseRequest {
        skillId = required(skillId, "skillId");
        version = required(version, "version");
        if (targetEnvironment == null) throw new IllegalArgumentException("targetEnvironment is required");
        sourceAssessmentId = optional(sourceAssessmentId, "sourceAssessmentId");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
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
