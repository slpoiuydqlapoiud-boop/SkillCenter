package com.huawei.skillcenter.relationship;

public record SkillRelationImpactNode(
        String relationId,
        String skillId,
        String version,
        SkillRelationType relationType,
        int depth,
        String status,
        boolean productionPromoted,
        long activeInstallationCount
) {
}
