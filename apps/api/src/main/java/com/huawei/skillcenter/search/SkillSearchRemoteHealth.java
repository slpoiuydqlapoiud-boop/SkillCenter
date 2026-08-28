package com.huawei.skillcenter.search;

/** Optional health contract for search implementations backed by an external service. */
public interface SkillSearchRemoteHealth {
    SkillSearchProbeResult probe();

    boolean probeFresh();
}
