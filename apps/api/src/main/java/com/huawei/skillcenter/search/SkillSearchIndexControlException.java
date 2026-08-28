package com.huawei.skillcenter.search;

/** Stable API boundary error that deliberately omits source and exception details. */
public final class SkillSearchIndexControlException extends RuntimeException {
    private final String code;

    public SkillSearchIndexControlException(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
