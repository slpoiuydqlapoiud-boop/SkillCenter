package com.huawei.skillcenter.lifecycle;

/** Signed source-minus-projection count differences. */
public record SkillLifecycleProjectionCountDelta(
        int skillCount,
        int versionCount,
        int releaseCount,
        int scopeCount,
        int relationCount) {
}
