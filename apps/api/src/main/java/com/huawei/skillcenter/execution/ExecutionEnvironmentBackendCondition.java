package com.huawei.skillcenter.execution;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects the execution-environment repository without implicit fallback or dual writes. */
abstract class ExecutionEnvironmentBackendCondition implements Condition {
    private final String expectedBackend;

    ExecutionEnvironmentBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment()
                .getProperty("skill-center.execution-environment-backend", "json");
        if (!expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured))) return false;
        if (!"postgresql".equals(expectedBackend)) return true;
        String persistence = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    public static final class Json extends ExecutionEnvironmentBackendCondition {
        public Json() { super("json"); }
    }

    public static final class Postgresql extends ExecutionEnvironmentBackendCondition {
        public Postgresql() { super("postgresql"); }
    }

    public static final class Mysql extends ExecutionEnvironmentBackendCondition {
        public Mysql() { super("mysql"); }
    }
}
