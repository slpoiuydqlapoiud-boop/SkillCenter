package com.huawei.skillcenter.search;

import com.huawei.skillcenter.skill.SkillRecord;

import java.util.Optional;

public interface SkillSearchDocumentSource {
    SkillSearchDocumentSnapshot snapshot();

    Optional<SkillRecord> findRecord(String skillId);
}
