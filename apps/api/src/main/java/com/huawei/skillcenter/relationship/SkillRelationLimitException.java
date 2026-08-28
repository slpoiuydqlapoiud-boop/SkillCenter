package com.huawei.skillcenter.relationship;

public class SkillRelationLimitException extends RuntimeException {
    public SkillRelationLimitException() {
        super("Skill relation impact limits are invalid");
    }
}
