package com.huawei.skillcenter.access;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for Skill visibility and ownership metadata. */
public interface SkillScopeRepository {
    Optional<SkillScope> find(String skillId);

    List<SkillScope> findAll();

    SkillScope create(SkillScope scope);

    SkillScope replace(SkillScope scope, int expectedRevision);
}
