package com.huawei.skillcenter.access;

public class SkillNotVisibleException extends RuntimeException {
    public SkillNotVisibleException() {
        super("Skill is not visible");
    }
}
