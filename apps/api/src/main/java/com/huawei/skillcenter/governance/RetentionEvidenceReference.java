package com.huawei.skillcenter.governance;

import java.util.Locale;

/** A normalized, auditable reference from a lifecycle asset to retained evidence. */
public record RetentionEvidenceReference(
        String sourceType,
        String sourceId,
        String evidenceType,
        String evidenceId
) implements Comparable<RetentionEvidenceReference> {
    public RetentionEvidenceReference {
        sourceType = normalizeCode(sourceType, "sourceType");
        sourceId = normalizeIdentifier(sourceId, "sourceId");
        evidenceType = normalizeCode(evidenceType, "evidenceType");
        evidenceId = normalizeIdentifier(evidenceId, "evidenceId");
    }

    public String canonical() {
        return sourceType + "|" + sourceId + "|" + evidenceType + "|" + evidenceId;
    }

    @Override
    public int compareTo(RetentionEvidenceReference other) {
        return canonical().compareTo(other.canonical());
    }

    private static String normalizeCode(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 64
                || !normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a stable bounded code");
        }
        return normalized;
    }

    private static String normalizeIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
