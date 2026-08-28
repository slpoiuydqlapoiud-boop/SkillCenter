package com.huawei.skillcenter.access;

import com.huawei.skillcenter.search.SkillSearchRefreshEvent;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for Skill visibility and ownership metadata. */
public interface SkillScopeRepository {
    Optional<SkillScope> find(String skillId);

    List<SkillScope> findAll();

    SkillScope create(SkillScope scope);

    default SkillScope create(SkillScope scope, SkillSearchRefreshEvent refreshEvent) {
        return create(scope);
    }

    SkillScope replace(SkillScope scope, int expectedRevision);

    default SkillScope replace(SkillScope scope, int expectedRevision, SkillSearchRefreshEvent refreshEvent) {
        return replace(scope, expectedRevision);
    }
}
