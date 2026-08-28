package com.huawei.skillcenter.relationship;

public class SkillRelationVersionNotFoundException extends RuntimeException {
    public SkillRelationVersionNotFoundException() {
        super("Skill relation version was not found");
    }
}
