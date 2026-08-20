package com.huawei.skillcenter.skill;

public class SkillNotFoundException extends RuntimeException {
    public SkillNotFoundException(String skillId) {
        super("Skill not found: " + skillId);
    }
}
