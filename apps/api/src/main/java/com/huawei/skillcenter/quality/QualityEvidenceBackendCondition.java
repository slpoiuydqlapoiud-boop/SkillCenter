package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.persistence.PersistenceControlProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Selects the quality evidence backend using the persistence contract's normalization. */
abstract class QualityEvidenceBackendCondition implements Condition {
    private final String expectedBackend;

    QualityEvidenceBackendCondition(String expectedBackend) {
        this.expectedBackend = expectedBackend;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty("skill-center.quality-evidence-backend", "json");
        return expectedBackend.equals(PersistenceControlProperties.normalizeBackendValue(configured));
    }

    public static final class Json extends QualityEvidenceBackendCondition {
        public Json() { super("json"); }
    }

    public static final class Postgresql extends QualityEvidenceBackendCondition {
        public Postgresql() { super("postgresql"); }
    }

    public static final class Mysql extends QualityEvidenceBackendCondition {
        public Mysql() { super("mysql"); }
    }
}
