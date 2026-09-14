package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects the optimization work-item repository only when its backend is explicitly configured. */
abstract class OptimizationWorkItemBackendCondition implements Condition {
    private final String expectedBackend;

    OptimizationWorkItemBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment()
                .getProperty("skill-center.optimization-work-item-backend", "json");
        if (!expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured))) return false;
        if (!"postgresql".equals(expectedBackend)) return true;
        String persistence = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    public static final class Json extends OptimizationWorkItemBackendCondition {
        public Json() { super("json"); }
    }

    public static final class Postgresql extends OptimizationWorkItemBackendCondition {
        public Postgresql() { super("postgresql"); }
    }

    public static final class Mysql extends OptimizationWorkItemBackendCondition {
        public Mysql() { super("mysql"); }
    }
}
