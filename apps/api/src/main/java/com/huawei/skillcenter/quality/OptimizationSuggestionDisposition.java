package com.huawei.skillcenter.quality;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** Persisted human disposition for a generated optimization suggestion. */
public record OptimizationSuggestionDisposition(
        String skillId,
        String version,
        String suggestionId,
        String status,
        String note,
        String actorId,
        String actorRole,
        Instant updatedAt,
        String evidenceType,
        String evidenceId
) {
    public static final String OPEN = "OPEN";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String DISMISSED = "DISMISSED";
    public static final String RESOLVED = "RESOLVED";
    public static final Set<String> STATUSES = Set.of(OPEN, ACKNOWLEDGED, DISMISSED, RESOLVED);
    public static final String NONE = "NONE";
    public static final String EVALUATION_RUN = "EVALUATION_RUN";
    public static final String QUALITY_SNAPSHOT = "QUALITY_SNAPSHOT";
    public static final String BENCHMARK = "BENCHMARK";
    public static final Set<String> EVIDENCE_TYPES = Set.of(NONE, EVALUATION_RUN, QUALITY_SNAPSHOT, BENCHMARK);

    public OptimizationSuggestionDisposition(String skillId, String version, String suggestionId, String status,
                                             String note, String actorId, String actorRole, Instant updatedAt) {
        this(skillId, version, suggestionId, status, note, actorId, actorRole, updatedAt, NONE, "");
    }

    public OptimizationSuggestionDisposition {
        requireText(skillId, "skillId");
        requireText(version, "version");
        requireText(suggestionId, "suggestionId");
        status = normalizeStatus(status);
        note = note == null ? "" : note.trim();
        if (note.length() > 500) {
            throw new IllegalArgumentException("note must not exceed 500 characters");
        }
        requireText(actorId, "actorId");
        requireText(actorRole, "actorRole");
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        evidenceType = normalizeEvidenceType(evidenceType);
        evidenceId = normalizeEvidenceId(evidenceType, evidenceId);
    }

    public static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be one of OPEN, ACKNOWLEDGED, DISMISSED or RESOLVED");
        }
        return normalized;
    }

    public static String normalizeEvidenceType(String value) {
        String normalized = value == null || value.isBlank() ? NONE : value.trim().toUpperCase(Locale.ROOT);
        if (!EVIDENCE_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("evidenceType must be one of NONE, EVALUATION_RUN, QUALITY_SNAPSHOT or BENCHMARK");
        }
        return normalized;
    }

    public static String normalizeEvidenceId(String evidenceType, String value) {
        String normalizedType = normalizeEvidenceType(evidenceType);
        String normalized = value == null ? "" : value.trim();
        if (normalizedType.equals(NONE)) {
            if (!normalized.isEmpty()) {
                throw new IllegalArgumentException("evidenceId must be blank when evidenceType is NONE");
            }
            return "";
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("evidenceId is required when evidenceType is linked");
        }
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("evidenceId contains unsupported characters or is too long");
        }
        return normalized;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
