package com.huawei.skillcenter.relationship;

public class SkillRelationCycleException extends RuntimeException {
    public SkillRelationCycleException() {
        super("Skill relation graph contains a cycle");
    }
}
