package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects the governance aggregate backend without allowing an implicit PostgreSQL fallback. */
abstract class GovernanceBackendCondition implements Condition {
    private final String expectedBackend;

    GovernanceBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty("skill-center.governance-backend");
        if (configured == null || configured.isBlank()) {
            configured = context.getEnvironment()
                    .getProperty("skill-center.persistence.governance-backend", "json");
        }
        if (!expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured))) {
            return false;
        }
        if (!"postgresql".equals(expectedBackend)) {
            return true;
        }
        String persistence = context.getEnvironment()
                .getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    static final class Json extends GovernanceBackendCondition {
        Json() {
            super("json");
        }
    }

    static final class Postgresql extends GovernanceBackendCondition {
        Postgresql() {
            super("postgresql");
        }
    }
}
