package com.huawei.skillcenter.search;

import java.time.Instant;

/** Safe metadata returned by one bounded refresh-journal cleanup attempt. */
public record SkillSearchRefreshCleanupResult(int deletedCount, long consumerWatermark, Instant cutoff) {
    public SkillSearchRefreshCleanupResult {
        if (deletedCount < 0) throw new IllegalArgumentException("deletedCount must be non-negative");
        if (consumerWatermark < 0) throw new IllegalArgumentException("consumerWatermark must be non-negative");
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
    }

    public static SkillSearchRefreshCleanupResult none(Instant cutoff) {
        return new SkillSearchRefreshCleanupResult(0, 0L, cutoff);
    }
}
