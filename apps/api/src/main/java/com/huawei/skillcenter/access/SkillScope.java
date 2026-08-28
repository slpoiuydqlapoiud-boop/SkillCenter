package com.huawei.skillcenter.access;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

public record SkillScope(
        String skillId,
        SkillVisibility visibility,
        String ownerTeamId,
        List<String> maintainerUserIds,
        int revision,
        String declaredBy,
        Instant declaredAt,
        String updatedBy,
        Instant updatedAt
) {
    public SkillScope {
        skillId = normalizeRequiredIdentifier(skillId, "skillId");
        if (visibility == null) {
            throw new IllegalArgumentException("visibility is required");
        }
        ownerTeamId = normalizeOptionalIdentifier(ownerTeamId, "ownerTeamId");
        maintainerUserIds = normalizeMaintainers(maintainerUserIds);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        declaredBy = normalizeRequiredIdentifier(declaredBy, "declaredBy");
        if (declaredAt == null) {
            throw new IllegalArgumentException("declaredAt is required");
        }
        updatedBy = normalizeRequiredIdentifier(updatedBy, "updatedBy");
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt is required");
        }
        if (updatedAt.isBefore(declaredAt)) {
            throw new IllegalArgumentException("updatedAt must not be before declaredAt");
        }
        validateOwnershipRules(visibility, ownerTeamId, maintainerUserIds);
    }

    SkillScope withRevision(int nextRevision, Instant nextUpdatedAt) {
        return new SkillScope(skillId, visibility, ownerTeamId, maintainerUserIds,
                nextRevision, declaredBy, declaredAt, updatedBy, nextUpdatedAt);
    }

    static void validateOwnershipRules(SkillVisibility visibility, String ownerTeamId, List<String> maintainerUserIds) {
        // Store/model validation stays structural; active-team existence is enforced by the authorization layer in Task 2.
        if (visibility == SkillVisibility.TEAM && ownerTeamId.isBlank()) {
            throw new IllegalArgumentException("ownerTeamId is required when visibility is TEAM");
        }
        if (visibility == SkillVisibility.RESTRICTED && maintainerUserIds.isEmpty()) {
            throw new IllegalArgumentException("maintainerUserIds must not be empty when visibility is RESTRICTED");
        }
    }

    static String normalizeRequiredIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    static String normalizeOptionalIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return normalizeRequiredIdentifier(value, field);
    }

    static List<String> normalizeMaintainers(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(normalizeRequiredIdentifier(value, "maintainerUserIds"));
        }
        return List.copyOf(normalized);
    }
}
