package com.huawei.skillcenter.lifecycle;

public class SkillLifecycleProjectionSourceInvalidException extends RuntimeException {
    private static final String CODE = "SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID";
    private static final String MESSAGE = "Skill lifecycle projection source is invalid";

    public SkillLifecycleProjectionSourceInvalidException() {
        super(MESSAGE);
    }

    public String code() {
        return CODE;
    }

    public String getCode() {
        return CODE;
    }
}
