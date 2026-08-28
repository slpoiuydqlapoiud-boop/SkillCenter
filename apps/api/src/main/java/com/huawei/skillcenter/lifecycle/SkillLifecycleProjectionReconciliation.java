package com.huawei.skillcenter.lifecycle;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** Safe operational view of source drift and imported projection freshness. */
public record SkillLifecycleProjectionReconciliation(
        String backend,
        String state,
        String reasonCode,
        String schemaVersion,
        long revision,
        String projectedSourceSha256,
        String sourceSha256,
        Instant sourceGeneratedAt,
        Instant importedAt,
        Instant observedAt,
        Long projectionAgeSeconds,
        long maxProjectionAgeSeconds,
        SkillLifecycleProjectionCounts sourceCounts,
        SkillLifecycleProjectionCounts projectedCounts,
        SkillLifecycleProjectionCountDelta countDelta) {
    private static final Set<String> STATES = Set.of(
            "HEALTHY", "DRIFTED", "STALE", "LIVE_SOURCE", "NOT_IMPORTED", "NOT_READY");
    private static final Set<String> REASONS = Set.of(
            "",
            "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED",
            "SKILL_LIFECYCLE_PROJECTION_COUNT_MISMATCH",
            "SKILL_LIFECYCLE_PROJECTION_SOURCE_STALE",
            "SKILL_LIFECYCLE_PROJECTION_NOT_IMPORTED",
            "SKILL_LIFECYCLE_PROJECTION_NOT_READY");

    public SkillLifecycleProjectionReconciliation {
        backend = normalizeBackend(backend);
        state = STATES.contains(state) ? state : "NOT_READY";
        reasonCode = REASONS.contains(reasonCode) ? reasonCode : "SKILL_LIFECYCLE_PROJECTION_NOT_READY";
        schemaVersion = schemaVersion == null || schemaVersion.matches("[0-9]+") ? schemaVersion : null;
        if (revision < 0L) throw new IllegalArgumentException("revision must be non-negative");
        projectedSourceSha256 = safeSha256(projectedSourceSha256);
        sourceSha256 = safeSha256(sourceSha256);
        if (sourceGeneratedAt == null || observedAt == null) {
            throw new IllegalArgumentException("sourceGeneratedAt and observedAt are required");
        }
        if (projectionAgeSeconds != null && projectionAgeSeconds < 0L) {
            throw new IllegalArgumentException("projectionAgeSeconds must be non-negative");
        }
        if (maxProjectionAgeSeconds < 60L) {
            throw new IllegalArgumentException("maxProjectionAgeSeconds must be at least 60");
        }
        if (sourceCounts == null || projectedCounts == null || countDelta == null) {
            throw new IllegalArgumentException("reconciliation counts are required");
        }
    }

    private static String normalizeBackend(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Set.of("json", "postgresql").contains(normalized) ? normalized : "unknown";
    }

    private static String safeSha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}") ? value : "0".repeat(64);
    }
}
