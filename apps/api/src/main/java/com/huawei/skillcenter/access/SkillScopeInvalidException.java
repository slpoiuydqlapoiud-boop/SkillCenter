package com.huawei.skillcenter.access;

public class SkillScopeInvalidException extends RuntimeException {
    public SkillScopeInvalidException() {
        super("Skill scope is invalid");
    }

    public SkillScopeInvalidException(Throwable cause) {
        super("Skill scope is invalid", cause);
    }
}
