package com.huawei.skillcenter.lifecycle;

import java.time.Instant;

public record SkillLifecycleProjectionPreflight(
        String backend,
        long currentRevision,
        String currentSourceSha256,
        String sourceSha256,
        Instant sourceGeneratedAt,
        int skillCount,
        int versionCount,
        int releaseCount,
        int scopeCount,
        int relationCount,
        boolean matchesCurrentProjection,
        String reasonCode
) {
    public SkillLifecycleProjectionPreflight {
        backend = backend == null || backend.isBlank() ? "unknown" : backend.trim().toLowerCase(java.util.Locale.ROOT);
        currentRevision = Math.max(0L, currentRevision);
        currentSourceSha256 = safeSha256(currentSourceSha256);
        sourceSha256 = safeSha256(sourceSha256);
        if (sourceGeneratedAt == null) {
            throw new IllegalArgumentException("sourceGeneratedAt is required");
        }
        skillCount = requireNonNegative(skillCount, "skillCount");
        versionCount = requireNonNegative(versionCount, "versionCount");
        releaseCount = requireNonNegative(releaseCount, "releaseCount");
        scopeCount = requireNonNegative(scopeCount, "scopeCount");
        relationCount = requireNonNegative(relationCount, "relationCount");
        reasonCode = sanitizeReasonCode(reasonCode, matchesCurrentProjection);
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private static String sanitizeReasonCode(String value, boolean matchesCurrentProjection) {
        if (matchesCurrentProjection) {
            return "";
        }
        return "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED".equals(value)
                ? value
                : "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED";
    }

    private static String safeSha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}") ? value : "0".repeat(64);
    }
}
