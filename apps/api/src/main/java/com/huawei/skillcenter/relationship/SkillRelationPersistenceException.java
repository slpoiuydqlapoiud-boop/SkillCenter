package com.huawei.skillcenter.relationship;

public class SkillRelationPersistenceException extends RuntimeException {
    public SkillRelationPersistenceException(Throwable cause) {
        super("Skill relation state is unavailable", cause);
    }
}
