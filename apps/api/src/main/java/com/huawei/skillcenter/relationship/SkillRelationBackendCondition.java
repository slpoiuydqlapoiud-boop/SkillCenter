package com.huawei.skillcenter.relationship;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects exactly one Skill relation repository without implicit fallback. */
abstract class SkillRelationBackendCondition implements Condition {
    private final String expected;

    SkillRelationBackendCondition(String expected) {
        this.expected = expected;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String selected = context.getEnvironment()
                .getProperty("skill-center.skill-relation-backend", "json")
                .trim().toLowerCase();
        if (!expected.equals(selected)) return false;
        if (!"postgresql".equals(expected)) return true;
        return "postgresql".equals(context.getEnvironment()
                .getProperty("skill-center.persistence.backend", "json")
                .trim().toLowerCase());
    }

    static final class Json extends SkillRelationBackendCondition {
        Json() { super("json"); }
    }

    static final class Postgresql extends SkillRelationBackendCondition {
        Postgresql() { super("postgresql"); }
    }
}
