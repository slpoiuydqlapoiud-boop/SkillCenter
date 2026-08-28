package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.relationship.SkillRelationStatus;
import com.huawei.skillcenter.relationship.SkillRelationType;
import java.time.Instant;

public record SkillLifecycleRelationRow(
        String relationId, String sourceSkillId, String sourceVersion, String targetSkillId,
        String targetVersion, SkillRelationType relationType, SkillRelationStatus status,
        Instant declaredAt, Instant retiredAt
) {
    public SkillLifecycleRelationRow {
        relationId = SkillLifecycleProjectionInput.requireIdentifier(relationId, "relationId");
        sourceSkillId = SkillLifecycleProjectionInput.requireIdentifier(sourceSkillId, "sourceSkillId");
        sourceVersion = SkillLifecycleProjectionInput.requireVersion(sourceVersion, "sourceVersion");
        targetSkillId = SkillLifecycleProjectionInput.requireIdentifier(targetSkillId, "targetSkillId");
        targetVersion = SkillLifecycleProjectionInput.requireVersion(targetVersion, "targetVersion");
        if (sourceSkillId.equals(targetSkillId) && sourceVersion.equals(targetVersion)) {
            throw new IllegalArgumentException("source and target version must be different");
        }
        if (relationType == null) throw new IllegalArgumentException("relationType is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        declaredAt = SkillLifecycleProjectionInput.requireInstant(declaredAt, "declaredAt");
    }
}
