package com.huawei.skillcenter.access;

import java.time.Instant;
import java.util.List;

public record SkillScopeMutation(
        SkillVisibility visibility,
        String ownerTeamId,
        List<String> maintainerUserIds,
        int revision,
        String declaredBy,
        String updatedBy
) {
    public SkillScopeMutation {
        if (visibility == null) {
            throw new IllegalArgumentException("visibility is required");
        }
        ownerTeamId = SkillScope.normalizeOptionalIdentifier(ownerTeamId, "ownerTeamId");
        maintainerUserIds = SkillScope.normalizeMaintainers(maintainerUserIds);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        declaredBy = SkillScope.normalizeRequiredIdentifier(declaredBy, "declaredBy");
        updatedBy = SkillScope.normalizeRequiredIdentifier(updatedBy, "updatedBy");
        SkillScope.validateOwnershipRules(visibility, ownerTeamId, maintainerUserIds);
    }

    public SkillScope toScope(String skillId, int nextRevision, Instant declaredAt, Instant updatedAt) {
        return new SkillScope(skillId, visibility, ownerTeamId, maintainerUserIds, nextRevision,
                declaredBy, declaredAt, updatedBy, updatedAt);
    }
}
