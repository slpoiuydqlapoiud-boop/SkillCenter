package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Safe aggregate describing whether the optimization queue is making progress. */
public record OptimizationWorkItemStaleness(
        String status,
        String reasonCode,
        Instant evaluatedAt,
        long staleThresholdSeconds,
        int activeCount,
        int staleCount,
        Map<String, Integer> staleByStatus,
        Map<String, Integer> staleByOwner,
        Map<String, Integer> staleBySeverity,
        List<OptimizationWorkItemStaleItem> staleItems) {
    public OptimizationWorkItemStaleness {
        status = switch (status == null ? "" : status.trim().toUpperCase()) {
            case "HEALTHY", "DEGRADED", "NOT_READY" -> status.trim().toUpperCase();
            default -> "NOT_READY";
        };
        reasonCode = safeReason(reasonCode);
        evaluatedAt = evaluatedAt == null ? Instant.EPOCH : evaluatedAt;
        staleThresholdSeconds = Math.max(60, staleThresholdSeconds);
        activeCount = Math.max(0, activeCount);
        staleCount = Math.max(0, staleCount);
        staleByStatus = safeCounts(staleByStatus);
        staleByOwner = safeCounts(staleByOwner);
        staleBySeverity = safeCounts(staleBySeverity);
        staleItems = staleItems == null ? List.of() : staleItems.stream().limit(100).toList();
    }

    private static String safeReason(String value) {
        String normalized = value == null ? "OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128
                || normalized.chars().anyMatch(Character::isISOControl)) {
            return "OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE";
        }
        return normalized;
    }

    private static Map<String, Integer> safeCounts(Map<String, Integer> values) {
        if (values == null) return Map.of();
        TreeMap<String, Integer> normalized = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 128
                    || key.chars().anyMatch(Character::isISOControl)) return;
            normalized.put(key.trim(), Math.max(0, value == null ? 0 : value));
        });
        return Map.copyOf(normalized);
    }
}
