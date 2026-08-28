package com.huawei.skillcenter.access;

public class SkillManageForbiddenException extends RuntimeException {
    public SkillManageForbiddenException() {
        super("Skill management is forbidden");
    }
}
