package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

abstract class SkillLifecycleProjectionBackendCondition implements Condition {
    private final String expectedBackend;

    SkillLifecycleProjectionBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment()
                .getProperty("skill-center.lifecycle-projection.backend", "json");
        return expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured));
    }

    static final class Json extends SkillLifecycleProjectionBackendCondition {
        Json() {
            super("json");
        }
    }

    static final class Postgresql extends SkillLifecycleProjectionBackendCondition {
        Postgresql() {
            super("postgresql");
        }
    }
}
