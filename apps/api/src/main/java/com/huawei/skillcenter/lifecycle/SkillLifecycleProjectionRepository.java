package com.huawei.skillcenter.lifecycle;

public interface SkillLifecycleProjectionRepository {
    SkillLifecycleProjectionStatus status();

    SkillLifecycleProjectionImportResult replace(SkillLifecycleProjectionSnapshot snapshot);

    java.util.List<SkillLifecycleProjectionView> findSkills(SkillLifecycleProjectionQuery query);

    SkillLifecycleImpactView findImpact(String skillId, String version);

    /** Import timestamp is available for persisted projections; live JSON remains source-backed. */
    default java.util.Optional<java.time.Instant> importedAt() {
        return java.util.Optional.empty();
    }
}
