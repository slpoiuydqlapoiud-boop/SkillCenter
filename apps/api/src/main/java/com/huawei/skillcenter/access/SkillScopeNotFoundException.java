package com.huawei.skillcenter.access;

public class SkillScopeNotFoundException extends RuntimeException {
    public SkillScopeNotFoundException() {
        super("Skill scope was not found");
    }
}
