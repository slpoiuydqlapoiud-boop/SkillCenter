package com.huawei.skillcenter.search;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects one explicit local or shared Skill search projection backend. */
abstract class SkillSearchBackendCondition implements Condition {
    private final String expectedBackend;

    SkillSearchBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty("skill-center.search-index-backend", "json");
        if (!expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured))) {
            return false;
        }
        if (!"postgresql".equals(expectedBackend)) {
            return true;
        }
        String persistence = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    static final class Json extends SkillSearchBackendCondition {
        Json() {
            super("json");
        }
    }

    static final class Postgresql extends SkillSearchBackendCondition {
        Postgresql() {
            super("postgresql");
        }
    }

    static final class Opensearch extends SkillSearchBackendCondition {
        Opensearch() {
            super("opensearch");
        }
    }
}
