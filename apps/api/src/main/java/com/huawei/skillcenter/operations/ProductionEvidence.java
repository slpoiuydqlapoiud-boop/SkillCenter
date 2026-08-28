package com.huawei.skillcenter.operations;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** Safe metadata for one production handoff evidence item; raw reports and secrets are never stored. */
public record ProductionEvidence(
        String evidenceId,
        String status,
        String ownerUserId,
        Instant verifiedAt,
        Instant expiresAt,
        String evidenceRef,
        String summary,
        int revision,
        String updatedBy,
        Instant updatedAt) {
    private static final Pattern SAFE_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)(password|secret|credential|bearer|token|private\\s+key|begin\\s+).*|https?://.*");

    public ProductionEvidence {
        evidenceId = ProductionEvidenceCatalog.requireId(evidenceId);
        status = normalizeStatus(status);
        ownerUserId = safeIdentifier(ownerUserId, "ownerUserId", status.equals("MISSING"));
        if (status.equals("ACCEPTED") && expiresAt == null) {
            throw new IllegalArgumentException("accepted evidence must have expiresAt");
        }
        evidenceRef = safeReference(evidenceRef, status.equals("MISSING"));
        summary = safeSummary(summary);
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        updatedBy = safeIdentifier(updatedBy, "updatedBy", false);
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!switch (normalized) {
            case "MISSING", "SUBMITTED", "ACCEPTED", "REJECTED" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("unsupported production evidence status");
        }
        return normalized;
    }

    private static String safeIdentifier(String value, String field, boolean allowBlank) {
        String normalized = value == null ? "" : value.trim();
        if (allowBlank && normalized.isBlank()) return "";
        if (normalized.isBlank() || normalized.length() > 80 || SENSITIVE_TEXT.matcher(normalized).find()
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains unsafe metadata");
        }
        return normalized;
    }

    private static String safeReference(String value, boolean allowBlank) {
        String normalized = value == null ? "" : value.trim();
        if (allowBlank && normalized.isBlank()) return "";
        if (!SAFE_REFERENCE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("evidenceRef must be an opaque safe reference");
        }
        return normalized;
    }

    private static String safeSummary(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 240 || normalized.chars().anyMatch(Character::isISOControl)
                || SENSITIVE_TEXT.matcher(normalized).find()) {
            throw new IllegalArgumentException("summary contains unsafe metadata");
        }
        return normalized;
    }
}
