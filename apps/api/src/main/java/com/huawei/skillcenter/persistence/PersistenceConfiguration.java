package com.huawei.skillcenter.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.file.Path;

@Configuration
public class PersistenceConfiguration {
    @Bean
    @Conditional(JsonBackendCondition.class)
    PersistenceBackend jsonPersistenceBackend() {
        return new JsonPersistenceBackend();
    }
    @Bean
    PersistenceIntegrityService persistenceIntegrityService(
            @Value("${skill-center.persistence.configured-root:${user.dir}}") String configuredRoot) {
        return new PersistenceIntegrityService(configuredRoot(configuredRoot));
    }

    @Bean
    PersistenceSnapshotService persistenceSnapshotService(
            PersistenceArtifactCatalog catalog,
            PersistenceControlProperties properties,
            PersistenceIntegrityService integrityService,
            @Value("${skill-center.persistence.configured-root:${user.dir}}") String configuredRoot) {
        Path root = configuredRoot(configuredRoot);
        return new PersistenceSnapshotService(catalog, properties, root, integrityService);
    }

    private Path configuredRoot(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("skill-center.persistence.configured-root must not be blank");
        }
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }

    static final class JsonBackendCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String backend = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
            return "json".equals(PersistenceControlProperties.normalizeBackendValue(backend));
        }
    }
}
