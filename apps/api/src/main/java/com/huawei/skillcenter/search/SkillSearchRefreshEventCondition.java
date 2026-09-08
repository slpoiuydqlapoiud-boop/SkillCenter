package com.huawei.skillcenter.search;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects the PostgreSQL refresh outbox independently from the search index backend. */
abstract class SkillSearchRefreshEventCondition implements Condition {
    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String persistence = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    static final class PostgresqlPersistence extends SkillSearchRefreshEventCondition {
    }
}
