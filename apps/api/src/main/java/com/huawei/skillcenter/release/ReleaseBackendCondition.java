package com.huawei.skillcenter.release;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class ReleaseBackendCondition implements Condition {
    private final String expectedBackend;

    protected ReleaseBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String release = PersistenceControlProperties.normalizeBackendValue(
                context.getEnvironment().getProperty("skill-center.release-backend", "json"));
        String persistence = PersistenceControlProperties.normalizeBackendValue(
                context.getEnvironment().getProperty("skill-center.persistence.backend", "json"));
        return expectedBackend.equals(release) && expectedBackend.equals(persistence);
    }

    static final class Postgresql extends ReleaseBackendCondition {
        Postgresql() { super("postgresql"); }
    }

    static final class Mysql extends ReleaseBackendCondition {
        Mysql() { super("mysql"); }
    }
}
