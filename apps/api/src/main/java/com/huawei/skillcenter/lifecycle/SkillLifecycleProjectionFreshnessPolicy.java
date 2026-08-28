package com.huawei.skillcenter.lifecycle;

/** Bounded freshness policy for an imported lifecycle projection. */
public record SkillLifecycleProjectionFreshnessPolicy(long maxProjectionAgeSeconds) {
    public SkillLifecycleProjectionFreshnessPolicy {
        if (maxProjectionAgeSeconds < 60L || maxProjectionAgeSeconds > 86_400L) {
            throw new IllegalArgumentException("maxProjectionAgeSeconds must be between 60 and 86400");
        }
    }

    public static SkillLifecycleProjectionFreshnessPolicy defaults() {
        return new SkillLifecycleProjectionFreshnessPolicy(900L);
    }
}
