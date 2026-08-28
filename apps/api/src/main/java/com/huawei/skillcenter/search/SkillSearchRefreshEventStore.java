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
}
