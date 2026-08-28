package com.huawei.skillcenter.relationship;

public record SkillRelationRequest(
        String sourceSkillId,
        String sourceVersion,
        String targetSkillId,
        String targetVersion,
        SkillRelationType relationType
) {
}
