package com.huawei.skillcenter.search;

import java.util.Optional;

/** Optional integration boundary for governed visibility metadata. */
@FunctionalInterface
public interface SkillSearchScopeProvider {
    Optional<SkillSearchScope> find(String skillId);

    static SkillSearchScopeProvider none() {
        return skillId -> Optional.empty();
    }
}
