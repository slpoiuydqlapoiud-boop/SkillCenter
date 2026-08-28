package com.huawei.skillcenter.lifecycle;

import java.time.Instant;
import java.util.List;

public record SkillLifecycleProjectionSnapshot(
        String sourceSha256, Instant sourceGeneratedAt, List<SkillLifecycleSkillRow> skills,
        List<SkillLifecycleVersionRow> versions, List<SkillLifecycleReleaseRow> releases,
        List<SkillLifecycleScopeRow> scopes, List<SkillLifecycleRelationRow> relations
) {
    public SkillLifecycleProjectionSnapshot {
        sourceSha256 = SkillLifecycleProjectionInput.requireSha256(sourceSha256, "sourceSha256");
        sourceGeneratedAt = SkillLifecycleProjectionInput.requireInstant(sourceGeneratedAt, "sourceGeneratedAt");
        skills = SkillLifecycleProjectionInput.copyRows(skills, "skills");
        versions = SkillLifecycleProjectionInput.copyRows(versions, "versions");
        releases = SkillLifecycleProjectionInput.copyRows(releases, "releases");
        scopes = SkillLifecycleProjectionInput.copyRows(scopes, "scopes");
        relations = SkillLifecycleProjectionInput.copyRows(relations, "relations");
    }
}
