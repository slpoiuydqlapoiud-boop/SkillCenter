package com.huawei.skillcenter.search;

import org.springframework.scheduling.annotation.Scheduled;

/** Replays the shared refresh journal with an in-process cursor and at-least-once semantics. */
public final class SkillSearchRefreshEventPoller {
    private static final String DEFAULT_CONSUMER_ID = "local";
    private final SkillSearchRefreshEventStore store;
    private final SkillSearchRefreshEventConsumer consumer;
    private final String consumerId;
    private final int batchSize;
    private volatile long cursor;
    private volatile boolean cursorLoaded;

    public SkillSearchRefreshEventPoller(SkillSearchRefreshEventStore store,
                                         SkillSearchRefreshEventConsumer consumer,
                                         int batchSize) {
        this(store, consumer, DEFAULT_CONSUMER_ID, batchSize);
    }

    public SkillSearchRefreshEventPoller(SkillSearchRefreshEventStore store,
                                         SkillSearchRefreshEventConsumer consumer,
                                         String consumerId,
                                         int batchSize) {
        if (store == null || consumer == null) throw new IllegalArgumentException("store and consumer are required");
        this.consumerId = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        if (batchSize < 1 || batchSize > 1_000) throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        this.store = store;
        this.consumer = consumer;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${skill-center.search-index-events.poll-interval-ms:5000}")
    public void pollScheduled() {
        try {
            runOnce();
        } catch (RuntimeException ignored) {
            // Keep the cursor unchanged so the next tick retries the failed batch.
        }
    }

    void runOnce() {
        try {
            if (!cursorLoaded) {
                long restored = store.loadCursor(consumerId);
                if (restored < 0) throw new IllegalStateException("cursor must be non-negative");
                cursor = restored;
                cursorLoaded = true;
            }
            for (SkillSearchRefreshEventStore.StoredSkillSearchRefreshEvent stored : store.findAfter(cursor, batchSize)) {
                consumer.onRefresh(stored.event());
                store.saveCursor(consumerId, stored.sequence());
                cursor = stored.sequence();
            }
        } catch (RuntimeException ignored) {
            // The cursor remains at the last successfully delivered event for retry.
        }
    }

    long cursor() {
        return cursor;
    }
}
