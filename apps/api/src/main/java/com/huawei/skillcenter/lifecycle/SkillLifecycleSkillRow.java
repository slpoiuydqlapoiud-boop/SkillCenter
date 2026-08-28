package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.access.SkillVisibility;

public record SkillLifecycleSkillRow(
        String skillId, String latestVersion, String latestStatus, int versionCount,
        int publishedVersionCount, int activeReleaseCount, SkillVisibility visibility,
        String ownerTeamId, int scopeRevision
) {
    public SkillLifecycleSkillRow {
        skillId = SkillLifecycleProjectionInput.requireIdentifier(skillId, "skillId");
        latestVersion = SkillLifecycleProjectionInput.requireVersion(latestVersion, "latestVersion");
        latestStatus = SkillLifecycleProjectionInput.requireCode(latestStatus, "latestStatus");
        versionCount = SkillLifecycleProjectionInput.requireNonNegativeInt(versionCount, "versionCount");
        publishedVersionCount = SkillLifecycleProjectionInput.requireNonNegativeInt(publishedVersionCount, "publishedVersionCount");
        activeReleaseCount = SkillLifecycleProjectionInput.requireNonNegativeInt(activeReleaseCount, "activeReleaseCount");
        if (visibility == null) throw new IllegalArgumentException("visibility is required");
        ownerTeamId = SkillLifecycleProjectionInput.optionalIdentifier(ownerTeamId, "ownerTeamId");
        scopeRevision = SkillLifecycleProjectionInput.requireNonNegativeInt(scopeRevision, "scopeRevision");
    }
}
