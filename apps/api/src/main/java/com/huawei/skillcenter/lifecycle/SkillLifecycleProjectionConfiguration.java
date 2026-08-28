package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.persistence.PersistenceBackend;
import com.huawei.skillcenter.persistence.PersistenceBackendStatus;
import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.release.ReleaseRecordRepository;
import com.huawei.skillcenter.relationship.SkillRelationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class SkillLifecycleProjectionConfiguration {
    private static final int MINIMUM_SCHEMA_VERSION = 3;

    @Bean
    SkillLifecycleProjectionSource skillLifecycleProjectionSource(
            ObjectProvider<GovernanceStore> governanceStore,
            ObjectProvider<ReleaseRecordRepository> releaseRecordStore,
            ObjectProvider<SkillScopeRepository> skillScopeStore,
            ObjectProvider<SkillRelationRepository> skillRelationStore,
            ObjectProvider<Clock> clock) {
        GovernanceStore resolvedGovernanceStore = governanceStore.getIfAvailable();
        ReleaseRecordRepository resolvedReleaseRecordStore = releaseRecordStore.getIfAvailable();
        SkillScopeRepository resolvedSkillScopeStore = skillScopeStore.getIfAvailable();
        SkillRelationRepository resolvedRelationStore = skillRelationStore.getIfAvailable();
        if (resolvedGovernanceStore == null
                || resolvedReleaseRecordStore == null
                || resolvedSkillScopeStore == null
                || resolvedRelationStore == null) {
            return null;
        }
        return new SkillLifecycleProjectionSource(
                resolvedGovernanceStore,
                resolvedReleaseRecordStore,
                resolvedSkillScopeStore,
                resolvedRelationStore,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @Conditional(SkillLifecycleProjectionBackendCondition.Json.class)
    JsonSkillLifecycleProjectionStore jsonSkillLifecycleProjectionStore(
            ObjectProvider<SkillLifecycleProjectionSource> source,
            ObjectProvider<Clock> clock) {
        SkillLifecycleProjectionSource resolvedSource = source.getIfAvailable();
        if (resolvedSource == null) {
            return null;
        }
        return new JsonSkillLifecycleProjectionStore(resolvedSource, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @Conditional(SkillLifecycleProjectionBackendCondition.Postgresql.class)
    @ConditionalOnBean({PersistenceBackend.class, JdbcTemplate.class, DataSourceTransactionManager.class})
    PostgresSkillLifecycleProjectionStore postgresSkillLifecycleProjectionStore(
            PersistenceBackend persistenceBackend,
            JdbcTemplate postgresJdbcTemplate,
            DataSourceTransactionManager postgresTransactionManager) {
        if (!isReadyForLifecycleProjection(persistenceBackend)) {
            return null;
        }
        return new PostgresSkillLifecycleProjectionStore(postgresJdbcTemplate, postgresTransactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    SkillLifecycleProjectionService skillLifecycleProjectionService(
            ObjectProvider<SkillLifecycleProjectionSource> source,
            ObjectProvider<SkillLifecycleProjectionRepository> repository,
            ObjectProvider<SkillAuthorizationService> authorizationService,
            ObjectProvider<Clock> clock,
            @Value("${skill-center.lifecycle-projection.max-source-age-seconds:900}") long maxSourceAgeSeconds) {
        SkillLifecycleProjectionSource resolvedSource = source.getIfAvailable();
        SkillLifecycleProjectionRepository resolvedRepository = repository.getIfAvailable();
        SkillAuthorizationService resolvedAuthorizationService = authorizationService.getIfAvailable();
        if (resolvedSource == null || resolvedRepository == null || resolvedAuthorizationService == null) {
            return null;
        }
        return new SkillLifecycleProjectionService(
                resolvedSource,
                resolvedRepository,
                resolvedAuthorizationService,
                clock.getIfAvailable(Clock::systemUTC),
                new SkillLifecycleProjectionFreshnessPolicy(maxSourceAgeSeconds));
    }

    private boolean isReadyForLifecycleProjection(PersistenceBackend persistenceBackend) {
        if (persistenceBackend == null || !"postgresql".equals(persistenceBackend.backendId())) {
            return false;
        }
        PersistenceBackendStatus status = persistenceBackend.status();
        return status != null
                && "READY".equals(status.state())
                && schemaVersionAtLeastV3(status.schemaVersion());
    }

    private boolean schemaVersionAtLeastV3(String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            return false;
        }
        String majorComponent = schemaVersion.split("\\.", 2)[0];
        try {
            return Integer.parseInt(majorComponent) >= MINIMUM_SCHEMA_VERSION;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
