package com.huawei.skillcenter.relationship;

public class SkillRelationNotFoundException extends RuntimeException {
    public SkillRelationNotFoundException() {
        super("Skill relation was not found");
    }
}
