package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects the production evidence store without implicit JSON fallback. */
abstract class ProductionEvidenceBackendCondition implements Condition {
    private final String expectedBackend;

    ProductionEvidenceBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment()
                .getProperty("skill-center.production-evidence-backend", "json");
        if (!expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured))) return false;
        if (!"postgresql".equals(expectedBackend)) return true;
        String persistence = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
        return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(persistence));
    }

    static final class Json extends ProductionEvidenceBackendCondition {
        Json() { super("json"); }
    }

    static final class Postgresql extends ProductionEvidenceBackendCondition {
        Postgresql() { super("postgresql"); }
    }

    static final class Mysql extends ProductionEvidenceBackendCondition {
        Mysql() { super("mysql"); }
    }
}
