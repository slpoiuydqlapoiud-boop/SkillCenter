package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects one shared backend for experiment, observation and assessment stores. */
abstract class OptimizationExperimentBackendCondition implements Condition {
    private final String expectedBackend;

    OptimizationExperimentBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment()
                .getProperty("skill-center.optimization-experiment-backend", "json");
        if (!expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured))) return false;
        if (!"postgresql".equals(expectedBackend)) return true;
        String persistence = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    static final class Json extends OptimizationExperimentBackendCondition {
        Json() { super("json"); }
    }

    static final class Postgresql extends OptimizationExperimentBackendCondition {
        Postgresql() { super("postgresql"); }
    }
}
