package com.huawei.skillcenter.lifecycle;

import java.util.Set;

public record SkillLifecycleProjectionImportResult(
        boolean imported,
        boolean idempotent,
        long revision,
        String sourceSha256,
        int skillCount,
        int versionCount,
        int releaseCount,
        int scopeCount,
        int relationCount,
        String reasonCode) {
    private static final Set<String> STABLE_REASON_CODES = Set.of(
            "",
            "SKILL_LIFECYCLE_PROJECTION_IMPORT_FAILED",
            "SKILL_LIFECYCLE_PROJECTION_NOT_READY",
            "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED",
            "SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID");

    public SkillLifecycleProjectionImportResult {
        revision = requireNonNegativeLong(revision, "revision");
        sourceSha256 = safeSha256(sourceSha256);
        skillCount = requireNonNegativeInt(skillCount, "skillCount");
        versionCount = requireNonNegativeInt(versionCount, "versionCount");
        releaseCount = requireNonNegativeInt(releaseCount, "releaseCount");
        scopeCount = requireNonNegativeInt(scopeCount, "scopeCount");
        relationCount = requireNonNegativeInt(relationCount, "relationCount");
        reasonCode = imported || idempotent ? "" : safeReasonCode(reasonCode);
    }

    @Override
    public String toString() {
        return "SkillLifecycleProjectionImportResult[imported=" + imported
                + ", idempotent=" + idempotent
                + ", revision=" + revision
                + ", skillCount=" + skillCount
                + ", versionCount=" + versionCount
                + ", releaseCount=" + releaseCount
                + ", scopeCount=" + scopeCount
                + ", relationCount=" + relationCount
                + ", reasonCode=" + reasonCode + "]";
    }

    private static String safeReasonCode(String value) {
        return STABLE_REASON_CODES.contains(value) ? value : "SKILL_LIFECYCLE_PROJECTION_IMPORT_FAILED";
    }

    private static String safeSha256(String value) {
        if (value != null && value.matches("[a-f0-9]{64}")) {
            return value;
        }
        return "0".repeat(64);
    }

    private static long requireNonNegativeLong(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    private static int requireNonNegativeInt(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }
}
