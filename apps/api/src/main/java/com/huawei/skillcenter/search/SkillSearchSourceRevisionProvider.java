package com.huawei.skillcenter.search;

/** Optional integration boundary for a monotonic governed-source revision. */
@FunctionalInterface
public interface SkillSearchSourceRevisionProvider {
    long sourceRevision();

    static SkillSearchSourceRevisionProvider zero() {
        return () -> 0L;
    }
}
