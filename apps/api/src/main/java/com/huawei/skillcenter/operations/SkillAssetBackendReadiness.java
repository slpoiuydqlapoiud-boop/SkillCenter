package com.huawei.skillcenter.operations;

/** Safe, metadata-only readiness projection for Skill asset persistence. */
public record SkillAssetBackendReadiness(String backend, String status, String reasonCode, String summary) {
    public SkillAssetBackendReadiness {
        backend = required(backend, "backend");
        status = required(status, "status");
        reasonCode = normalize(reasonCode);
        summary = normalize(summary);
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", "");
    }
}
