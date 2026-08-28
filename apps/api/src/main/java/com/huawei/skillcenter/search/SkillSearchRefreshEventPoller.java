package com.huawei.skillcenter.search;

import org.springframework.scheduling.annotation.Scheduled;

/** Replays the shared refresh journal with an in-process cursor and at-least-once semantics. */
public final class SkillSearchRefreshEventPoller {
    private final SkillSearchRefreshEventStore store;
    private final SkillSearchRefreshEventConsumer consumer;
    private final int batchSize;
    private volatile long cursor;

    public SkillSearchRefreshEventPoller(SkillSearchRefreshEventStore store,
                                         SkillSearchRefreshEventConsumer consumer,
                                         int batchSize) {
        if (store == null || consumer == null) throw new IllegalArgumentException("store and consumer are required");
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
            for (SkillSearchRefreshEventStore.StoredSkillSearchRefreshEvent stored : store.findAfter(cursor, batchSize)) {
                consumer.onRefresh(stored.event());
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
