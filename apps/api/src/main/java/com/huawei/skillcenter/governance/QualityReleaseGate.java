package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.release.ReleaseGateSnapshot;

import java.time.Instant;

/** Release-publish policy port. Implementations must fail closed when a known quality gate is blocked. */
@FunctionalInterface
public interface QualityReleaseGate {
    void ensurePublishable(String skillId, String version);

    default ReleaseGateSnapshot evaluate(String skillId, String version) {
        ensurePublishable(skillId, version);
        return ReleaseGateSnapshot.passed(Instant.now());
    }
}
