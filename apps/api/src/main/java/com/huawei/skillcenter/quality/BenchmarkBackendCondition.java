package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects the configured JSON or shared PostgreSQL Benchmark backend. */
abstract class BenchmarkBackendCondition implements Condition {
    private final String expectedBackend;

    BenchmarkBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty("skill-center.benchmark-backend", "json");
        if (!expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured))) return false;
        if (!"postgresql".equals(expectedBackend)) return true;
        String persistence = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    static final class Json extends BenchmarkBackendCondition {
        Json() { super("json"); }
    }

    static final class Postgresql extends BenchmarkBackendCondition {
        Postgresql() { super("postgresql"); }
    }
}
