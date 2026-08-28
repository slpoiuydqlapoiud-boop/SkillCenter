package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.access.SkillVisibility;
import java.time.Instant;

public record SkillLifecycleScopeRow(
        String skillId, SkillVisibility visibility, String ownerTeamId, int maintainerCount,
        int scopeRevision, Instant declaredAt, Instant updatedAt
) {
    public SkillLifecycleScopeRow {
        skillId = SkillLifecycleProjectionInput.requireIdentifier(skillId, "skillId");
        if (visibility == null) throw new IllegalArgumentException("visibility is required");
        ownerTeamId = SkillLifecycleProjectionInput.optionalIdentifier(ownerTeamId, "ownerTeamId");
        maintainerCount = SkillLifecycleProjectionInput.requireNonNegativeInt(maintainerCount, "maintainerCount");
        scopeRevision = SkillLifecycleProjectionInput.requireNonNegativeInt(scopeRevision, "scopeRevision");
        declaredAt = SkillLifecycleProjectionInput.requireInstant(declaredAt, "declaredAt");
        updatedAt = SkillLifecycleProjectionInput.requireInstant(updatedAt, "updatedAt");
    }
}
