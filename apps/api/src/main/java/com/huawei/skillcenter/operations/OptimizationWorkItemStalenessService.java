package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.quality.OptimizationWorkItem;
import com.huawei.skillcenter.quality.OptimizationWorkItemRepository;
import com.huawei.skillcenter.quality.OptimizationWorkItemStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Computes a read-only, bounded health view of the optimization work-item queue. */
@Service
public class OptimizationWorkItemStalenessService implements OptimizationWorkItemStalenessProbe {
    private static final long DEFAULT_THRESHOLD_SECONDS = 7 * 24 * 3600L;
    private static final long MAX_THRESHOLD_SECONDS = 365 * 24 * 3600L;
    private final OptimizationWorkItemRepository repository;
    private final long thresholdSeconds;
    private final Clock clock;

    @Autowired
    public OptimizationWorkItemStalenessService(
            OptimizationWorkItemRepository repository,
            @Value("${skill-center.operations.alerts.optimization-work-item-stale-seconds:604800}") long thresholdSeconds) {
        this(repository, thresholdSeconds, Clock.systemUTC());
    }

    public OptimizationWorkItemStalenessService(OptimizationWorkItemRepository repository,
                                                long thresholdSeconds, Clock clock) {
        this.repository = require(repository, "repository");
        this.thresholdSeconds = normalizeThreshold(thresholdSeconds);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public OptimizationWorkItemStaleness health() {
        Instant now = clock.instant();
        try {
            List<OptimizationWorkItem> values = repository.findAll("", "", "", "");
            int activeCount = 0;
            List<OptimizationWorkItemStaleItem> staleItems = new ArrayList<>();
            Map<String, Integer> byStatus = new HashMap<>();
            Map<String, Integer> byOwner = new HashMap<>();
            Map<String, Integer> bySeverity = new HashMap<>();
            int staleCount = 0;
            for (OptimizationWorkItem item : values == null ? List.<OptimizationWorkItem>of() : values) {
                if (item == null || OptimizationWorkItemStatus.isTerminal(item.status())) continue;
                activeCount++;
                long ageSeconds = ageSeconds(item.updatedAt(), now);
                if (ageSeconds < thresholdSeconds) continue;
                increment(byStatus, item.status());
                increment(byOwner, item.ownerId());
                increment(bySeverity, item.suggestionSeverity());
                staleCount++;
                if (staleItems.size() < 100) {
                    staleItems.add(new OptimizationWorkItemStaleItem(item.workItemId(), item.skillId(),
                            item.status(), item.ownerId(), item.suggestionSeverity(), item.updatedAt(), ageSeconds));
                }
            }
            staleItems.sort(Comparator.comparingLong(OptimizationWorkItemStaleItem::ageSeconds).reversed()
                    .thenComparing(OptimizationWorkItemStaleItem::workItemId));
            return new OptimizationWorkItemStaleness(
                    staleCount == 0 ? "HEALTHY" : "DEGRADED",
                    staleCount == 0 ? "OPTIMIZATION_WORK_ITEMS_HEALTHY" : "OPTIMIZATION_WORK_ITEMS_STALE",
                    now, thresholdSeconds, activeCount, staleCount, byStatus, byOwner, bySeverity, staleItems);
        } catch (RuntimeException ignored) {
            return unavailable(now);
        }
    }

    private long ageSeconds(Instant updatedAt, Instant now) {
        if (updatedAt == null || updatedAt.isAfter(now)) return 0;
        return Math.max(0, Duration.between(updatedAt, now).getSeconds());
    }

    private void increment(Map<String, Integer> values, String key) {
        if (key != null && !key.isBlank()) values.merge(key, 1, Integer::sum);
    }

    private static long normalizeThreshold(long value) {
        if (value <= 0) return DEFAULT_THRESHOLD_SECONDS;
        return Math.min(MAX_THRESHOLD_SECONDS, Math.max(60, value));
    }

    private OptimizationWorkItemStaleness unavailable(Instant now) {
        return new OptimizationWorkItemStaleness("NOT_READY", "OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE",
                now, thresholdSeconds, 0, 0, Map.of(), Map.of(), Map.of(), List.of());
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
