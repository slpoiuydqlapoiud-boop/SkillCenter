package com.huawei.skillcenter.release;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class ReleaseBackendCondition implements Condition {
    private final boolean postgresql;

    protected ReleaseBackendCondition(boolean postgresql) {
        this.postgresql = postgresql;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String release = PersistenceControlProperties.normalizeBackendValue(
                context.getEnvironment().getProperty("skill-center.release-backend", "json"));
        String persistence = PersistenceControlProperties.normalizeBackendValue(
                context.getEnvironment().getProperty("skill-center.persistence.backend", "json"));
        return postgresql == ("postgresql".equals(release) && "postgresql".equals(persistence));
    }

    static final class Postgresql extends ReleaseBackendCondition {
        Postgresql() { super(true); }
    }
}
