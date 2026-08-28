package com.huawei.skillcenter.search;

/** Optional health contract for search implementations backed by an external service. */
public interface SkillSearchRemoteHealth {
    SkillSearchProbeResult probe();

    boolean probeFresh();

    /** Non-secret identity of the remote configuration used to create probe evidence. */
    default String probeIdentity() {
        return "";
    }
}
