package com.huawei.skillcenter.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Bounded, opt-in cleanup of acknowledged PostgreSQL refresh-journal events. */
public final class SkillSearchRefreshEventRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillSearchRefreshEventRetentionScheduler.class);
    private static final long MAX_SCHEDULE_INTERVAL_MS = 86_400_000L;
    private final SkillSearchRefreshEventStore store;
    private final int retentionDays;
    private final int batchSize;
    private final Clock clock;
    private final long cleanupIntervalMs;
    private final long cleanupInitialDelayMs;

    public SkillSearchRefreshEventRetentionScheduler(SkillSearchRefreshEventStore store,
                                                     int retentionDays,
                                                     int batchSize,
                                                     Clock clock) {
        this(store, retentionDays, batchSize, clock, 3_600_000L, 3_600_000L);
    }

    public SkillSearchRefreshEventRetentionScheduler(SkillSearchRefreshEventStore store,
                                                     int retentionDays,
                                                     int batchSize,
                                                     Clock clock,
                                                     long cleanupIntervalMs,
                                                     long cleanupInitialDelayMs) {
        if (store == null) throw new IllegalArgumentException("store is required");
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException("retentionDays must be between 1 and 3650");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        }
        if (clock == null) throw new IllegalArgumentException("clock is required");
        if (cleanupIntervalMs < 1_000L || cleanupIntervalMs > MAX_SCHEDULE_INTERVAL_MS) {
            throw new IllegalArgumentException("cleanupIntervalMs must be between 1000 and 86400000");
        }
        if (cleanupInitialDelayMs < 0L || cleanupInitialDelayMs > MAX_SCHEDULE_INTERVAL_MS) {
            throw new IllegalArgumentException("cleanupInitialDelayMs must be between 0 and 86400000");
        }
        this.store = store;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.clock = clock;
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.cleanupInitialDelayMs = cleanupInitialDelayMs;
    }

    @Scheduled(
            fixedDelayString = "${skill-center.search-index-events.retention.cleanup-interval-ms:3600000}",
            initialDelayString = "${skill-center.search-index-events.retention.cleanup-initial-delay-ms:3600000}")
    public void runScheduled() {
        try {
            SkillSearchRefreshCleanupResult result = runOnce();
            if (result.deletedCount() > 0) {
                LOGGER.info("skill search refresh journal cleanup deletedCount={} consumerWatermark={}",
                        result.deletedCount(), result.consumerWatermark());
            }
        } catch (RuntimeException ignored) {
            LOGGER.warn("skill search refresh journal cleanup failed; future scheduled attempts will retry");
        }
    }

    SkillSearchRefreshCleanupResult runOnce() {
        Instant cutoff = Instant.now(clock).minus(retentionDays, ChronoUnit.DAYS);
        return store.purgeConsumedBefore(cutoff, batchSize);
    }
}
