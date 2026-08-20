package com.huawei.skillcenter.skill;

import java.util.Optional;

public interface SkillRepository {
    PageResult<SkillRecord> findPublished(SkillQuery query);

    Optional<SkillRecord> findDetail(String skillId);
}
