package com.huawei.skillcenter.lifecycle;

/** Stable, non-sensitive entity counts used by projection reconciliation. */
public record SkillLifecycleProjectionCounts(
        int skillCount,
        int versionCount,
        int releaseCount,
        int scopeCount,
        int relationCount) {
    public SkillLifecycleProjectionCounts {
        skillCount = requireNonNegative(skillCount, "skillCount");
        versionCount = requireNonNegative(versionCount, "versionCount");
        releaseCount = requireNonNegative(releaseCount, "releaseCount");
        scopeCount = requireNonNegative(scopeCount, "scopeCount");
        relationCount = requireNonNegative(relationCount, "relationCount");
    }

    public SkillLifecycleProjectionCountDelta subtract(SkillLifecycleProjectionCounts other) {
        if (other == null) throw new IllegalArgumentException("other counts are required");
        return new SkillLifecycleProjectionCountDelta(
                Math.subtractExact(skillCount, other.skillCount),
                Math.subtractExact(versionCount, other.versionCount),
                Math.subtractExact(releaseCount, other.releaseCount),
                Math.subtractExact(scopeCount, other.scopeCount),
                Math.subtractExact(relationCount, other.relationCount));
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }
}
