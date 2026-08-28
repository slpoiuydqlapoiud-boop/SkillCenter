package com.huawei.skillcenter.relationship;

import java.time.Instant;

public record SkillRelation(
        String relationId,
        String sourceSkillId,
        String sourceVersion,
        String targetSkillId,
        String targetVersion,
        SkillRelationType relationType,
        SkillRelationStatus status,
        String declaredBy,
        Instant declaredAt,
        String retiredBy,
        Instant retiredAt,
        String statusReason
) {
    public SkillRelation {
        relationId = requiredIdentifier(relationId, "relationId");
        sourceSkillId = requiredIdentifier(sourceSkillId, "sourceSkillId");
        sourceVersion = requiredText(sourceVersion, "sourceVersion");
        targetSkillId = requiredIdentifier(targetSkillId, "targetSkillId");
        targetVersion = requiredText(targetVersion, "targetVersion");
        if (sourceSkillId.equals(targetSkillId) && sourceVersion.equals(targetVersion)) {
            throw new IllegalArgumentException("source and target version must be different");
        }
        if (relationType == null) throw new IllegalArgumentException("relationType is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        declaredBy = requiredIdentifier(declaredBy, "declaredBy");
        if (declaredAt == null) throw new IllegalArgumentException("declaredAt is required");
        retiredBy = optionalIdentifier(retiredBy, "retiredBy");
        if (retiredBy.isBlank() != (retiredAt == null)) {
            throw new IllegalArgumentException("retiredBy and retiredAt must be provided together");
        }
        statusReason = optionalText(statusReason, "statusReason");
        if (status == SkillRelationStatus.ACTIVE && !retiredBy.isBlank()) {
            throw new IllegalArgumentException("active relation cannot be retired");
        }
        if (status == SkillRelationStatus.RETIRED && retiredBy.isBlank()) {
            throw new IllegalArgumentException("retired relation requires retirement metadata");
        }
    }

    public static SkillRelation create(String relationId, String sourceSkillId, String sourceVersion,
                                       String targetSkillId, String targetVersion, SkillRelationType relationType,
                                       String declaredBy, Instant declaredAt) {
        return new SkillRelation(relationId, sourceSkillId, sourceVersion, targetSkillId, targetVersion,
                relationType, SkillRelationStatus.ACTIVE, declaredBy, declaredAt, "", null, "");
    }

    public SkillRelation retire(String actor, String reason, Instant at) {
        if (status != SkillRelationStatus.ACTIVE) {
            throw new IllegalStateException("relation is already retired");
        }
        return new SkillRelation(relationId, sourceSkillId, sourceVersion, targetSkillId, targetVersion,
                relationType, SkillRelationStatus.RETIRED, declaredBy, declaredAt, requiredIdentifier(actor, "actor"),
                at == null ? Instant.now() : at, requiredText(reason, "reason"));
    }

    private static String requiredIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String optionalIdentifier(String value, String field) {
        if (value == null || value.isBlank()) return "";
        return requiredIdentifier(value, field);
    }

    private static String requiredText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 512) {
            throw new IllegalArgumentException(field + " must be bounded text");
        }
        return normalized;
    }

    private static String optionalText(String value, String field) {
        if (value == null || value.isBlank()) return "";
        return requiredText(value, field);
    }
}
