package com.huawei.skillcenter.search;

import java.time.Instant;
import java.util.List;

/** Durable, at-least-once journal boundary for cross-instance search refreshes. */
public interface SkillSearchRefreshEventStore {
    void append(SkillSearchRefreshEvent event);

    List<StoredSkillSearchRefreshEvent> findAfter(long sequence, int limit);

    /** Loads a durable per-instance cursor; local test stores may keep the default zero cursor. */
    default long loadCursor(String consumerId) {
        return 0L;
    }

    /** Persists a monotonic per-instance cursor after successful delivery. */
    default void saveCursor(String consumerId, long sequence) {
    }

    /** Registers a consumer if absent, but never resurrects a retired identity. */
    default SkillSearchRefreshConsumerState registerConsumer(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        return new SkillSearchRefreshConsumerState(bounded, loadCursor(bounded),
                SkillSearchRefreshConsumerStatus.ACTIVE, checkedNow, null);
    }

    /** Records liveness for an active consumer; durable implementations return RETIRED when applicable. */
    default SkillSearchRefreshConsumerState heartbeat(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        return new SkillSearchRefreshConsumerState(bounded, loadCursor(bounded),
                SkillSearchRefreshConsumerStatus.ACTIVE, checkedNow, null);
    }

    /** Explicit administrative reactivation; this is intentionally separate from poller registration. */
    default SkillSearchRefreshConsumerState activateConsumer(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        return new SkillSearchRefreshConsumerState(bounded, loadCursor(bounded),
                SkillSearchRefreshConsumerStatus.ACTIVE, checkedNow, null);
    }

    /** Explicit administrative retirement. */
    default SkillSearchRefreshConsumerState retireConsumer(String consumerId, Instant now) {
        String bounded = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        Instant checkedNow = requireInstant(now);
        return new SkillSearchRefreshConsumerState(bounded, loadCursor(bounded),
                SkillSearchRefreshConsumerStatus.RETIRED, checkedNow, checkedNow);
    }

    /** Lists only metadata needed by the administrative control plane. */
    default List<SkillSearchRefreshConsumerState> listConsumers() {
        return List.of();
    }

    /** Cleanup variant that may exclude only stale consumers already caught up to the deletion cutoff. */
    default SkillSearchRefreshCleanupResult purgeConsumedBefore(Instant cutoff, Instant activeSince, int limit) {
        return purgeConsumedBefore(cutoff, limit);
    }

    /**
     * Deletes only events that are older than the retention cutoff and already
     * acknowledged by every registered consumer. Implementations must keep the
     * no-consumer case safe by deleting nothing.
     */
    default SkillSearchRefreshCleanupResult purgeConsumedBefore(Instant cutoff, int limit) {
        return SkillSearchRefreshCleanupResult.none(cutoff);
    }

    record StoredSkillSearchRefreshEvent(long sequence, SkillSearchRefreshEvent event) {
        public StoredSkillSearchRefreshEvent {
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            if (event == null) throw new IllegalArgumentException("event is required");
        }
    }

    private static Instant requireInstant(Instant value) {
        if (value == null) throw new IllegalArgumentException("timestamp is required");
        return value;
    }
}
