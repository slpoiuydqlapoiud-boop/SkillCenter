package com.huawei.skillcenter.operations;

import java.time.Instant;

/** Redacted operational metadata for one stale optimization work item. */
public record OptimizationWorkItemStaleItem(
        String workItemId,
        String skillId,
        String status,
        String ownerId,
        String severity,
        Instant updatedAt,
        long ageSeconds) {
    public OptimizationWorkItemStaleItem {
        workItemId = safe(workItemId, "workItemId");
        skillId = safe(skillId, "skillId");
        status = safe(status, "status");
        ownerId = safe(ownerId, "ownerId");
        severity = safe(severity, "severity");
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        ageSeconds = Math.max(0, ageSeconds);
    }

    private static String safe(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains unsafe metadata");
        }
        return normalized;
    }
}
