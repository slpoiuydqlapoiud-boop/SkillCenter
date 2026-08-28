package com.huawei.skillcenter.lifecycle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record SkillLifecycleProjectionInput(
        List<SkillLifecycleSkillRow> skills,
        List<SkillLifecycleVersionRow> versions,
        List<SkillLifecycleReleaseRow> releases,
        List<SkillLifecycleScopeRow> scopes,
        List<SkillLifecycleRelationRow> relations
) {
    public SkillLifecycleProjectionInput {
        skills = copyRows(skills, "skills");
        versions = copyRows(versions, "versions");
        releases = copyRows(releases, "releases");
        scopes = copyRows(scopes, "scopes");
        relations = copyRows(relations, "relations");
    }

    static String requireIdentifier(String value, String field) {
        String normalized = requireText(value, field, 128);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    static String optionalIdentifier(String value, String field) {
        return value == null || value.isBlank() ? "" : requireIdentifier(value, field);
    }

    static String requireCode(String value, String field) {
        String normalized = requireText(value, field, 128);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a stable bounded code");
        }
        return normalized;
    }

    static String requireVersion(String value, String field) {
        return requireText(value, field, 128);
    }

    static String optionalVersion(String value, String field) {
        return value == null || value.isBlank() ? "" : requireVersion(value, field);
    }

    static String requireSha256(String value, String field) {
        String normalized = requireText(value, field, 64).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    static Instant requireInstant(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    static int requireNonNegativeInt(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    static long requireNonNegativeLong(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " exceeds maximum length");
        return normalized;
    }

    static <T> List<T> copyRows(List<T> rows, String field) {
        if (rows == null) throw new IllegalArgumentException(field + " is required");
        List<T> copy = new ArrayList<>(rows.size());
        for (T row : rows) {
            if (row == null) throw new IllegalArgumentException(field + " must not contain null rows");
            copy.add(row);
        }
        return List.copyOf(copy);
    }
}
