package com.huawei.skillcenter.search;

import java.time.Instant;

/** Metadata-only durable state for one cross-instance search refresh consumer. */
public record SkillSearchRefreshConsumerState(
        String consumerId,
        long lastEventSeq,
        SkillSearchRefreshConsumerStatus status,
        Instant lastSeenAt,
        Instant retiredAt) {
    public SkillSearchRefreshConsumerState {
        consumerId = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        if (lastEventSeq < 0) throw new IllegalArgumentException("lastEventSeq must be non-negative");
        if (status == null) throw new IllegalArgumentException("status is required");
        if (lastSeenAt == null) throw new IllegalArgumentException("lastSeenAt is required");
        if (status == SkillSearchRefreshConsumerStatus.ACTIVE && retiredAt != null) {
            throw new IllegalArgumentException("active consumer cannot have retiredAt");
        }
        if (status == SkillSearchRefreshConsumerStatus.RETIRED && retiredAt == null) {
            throw new IllegalArgumentException("retired consumer requires retiredAt");
        }
    }
}
