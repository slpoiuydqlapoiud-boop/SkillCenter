package com.huawei.skillcenter.relationship;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for version-to-version Skill relationships. */
public interface SkillRelationRepository {
    Optional<SkillRelation> find(String relationId);

    List<SkillRelation> findAll(String sourceSkillId, String sourceVersion,
                                String targetSkillId, String targetVersion,
                                SkillRelationStatus status);

    SkillRelation create(SkillRelation relation);

    SkillRelation replace(SkillRelation relation);
}
