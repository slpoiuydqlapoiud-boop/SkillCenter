package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.release.ReleaseEnvironment;
import com.huawei.skillcenter.release.ReleaseStatus;
import java.time.Instant;

public record SkillLifecycleReleaseRow(
        String releaseId, String skillId, String version, ReleaseEnvironment targetEnvironment,
        ReleaseStatus status, String gateOutcome, String sha256, Instant requestedAt, Instant approvedAt,
        Instant updatedAt, String sourceAssessmentId, String rollbackOfReleaseId,
        String rollbackTargetVersion, String rollbackTargetReleaseId
) {
    public SkillLifecycleReleaseRow {
        releaseId = SkillLifecycleProjectionInput.requireIdentifier(releaseId, "releaseId");
        skillId = SkillLifecycleProjectionInput.requireIdentifier(skillId, "skillId");
        version = SkillLifecycleProjectionInput.requireVersion(version, "version");
        if (targetEnvironment == null) throw new IllegalArgumentException("targetEnvironment is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        gateOutcome = SkillLifecycleProjectionInput.requireCode(gateOutcome, "gateOutcome");
        sha256 = SkillLifecycleProjectionInput.requireSha256(sha256, "sha256");
        requestedAt = SkillLifecycleProjectionInput.requireInstant(requestedAt, "requestedAt");
        updatedAt = SkillLifecycleProjectionInput.requireInstant(updatedAt, "updatedAt");
        sourceAssessmentId = SkillLifecycleProjectionInput.optionalIdentifier(sourceAssessmentId, "sourceAssessmentId");
        rollbackOfReleaseId = SkillLifecycleProjectionInput.optionalIdentifier(rollbackOfReleaseId, "rollbackOfReleaseId");
        rollbackTargetVersion = SkillLifecycleProjectionInput.optionalVersion(rollbackTargetVersion, "rollbackTargetVersion");
        rollbackTargetReleaseId = SkillLifecycleProjectionInput.optionalIdentifier(rollbackTargetReleaseId, "rollbackTargetReleaseId");
    }
}
