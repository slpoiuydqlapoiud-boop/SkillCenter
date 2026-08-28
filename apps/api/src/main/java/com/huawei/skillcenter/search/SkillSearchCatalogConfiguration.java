package com.huawei.skillcenter.search;

import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.skill.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class SkillSearchCatalogConfiguration {
    @Bean
    @Conditional(SkillSearchBackendCondition.Json.class)
    JsonSkillSearchIndex skillSearchIndex() {
        return new JsonSkillSearchIndex();
    }

    @Bean
    @Conditional(SkillSearchBackendCondition.Postgresql.class)
    JdbcSkillSearchIndex postgresSkillSearchIndex(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                   ObjectMapper mapper,
                                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new JdbcSkillSearchIndex(jdbc, mapper, transactionManager);
    }

    @Bean
    SkillSearchDocumentSource skillSearchDocumentSource(GovernanceStore store, SkillRepository repository,
                                                         SkillScopeRepository scopes) {
        return new GovernedSkillSearchDocumentSource(store, repository,
                skillId -> scopes.find(skillId).map(scope -> new SkillSearchScope(scope.skillId(),
                        scope.visibility().name(), scope.ownerTeamId())),
                () -> store.snapshot().audits().size());
    }

    @Bean
    SkillSearchRefreshCoordinator skillSearchRefreshCoordinator(SkillSearchIndex index,
                                                                 SkillSearchDocumentSource source) {
        return new SkillSearchRefreshCoordinator(index, source);
    }

    @Bean
    @Conditional(SkillSearchBackendCondition.Postgresql.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "skill-center.search-index-events.enabled", havingValue = "true")
    JdbcSkillSearchRefreshEventStore skillSearchRefreshEventStore(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new JdbcSkillSearchRefreshEventStore(jdbc, transactionManager);
    }

    @Bean
    @Conditional(SkillSearchBackendCondition.Postgresql.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "skill-center.search-index-events.enabled", havingValue = "true")
    SkillSearchRefreshEventPoller skillSearchRefreshEventPoller(SkillSearchRefreshEventStore store,
                                                                 SkillSearchRefreshCoordinator coordinator,
                                                                 @org.springframework.beans.factory.annotation.Value(
                                                                         "${skill-center.search-index-events.consumer-id:local}") String consumerId,
                                                                 @org.springframework.beans.factory.annotation.Value(
                                                                         "${skill-center.search-index-events.batch-size:100}") int batchSize) {
        return new SkillSearchRefreshEventPoller(store, coordinator, consumerId, batchSize);
    }

    @Bean
    @Conditional(SkillSearchBackendCondition.Postgresql.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = {"skill-center.search-index-events.enabled",
                    "skill-center.search-index-events.retention.scheduler-enabled"},
            havingValue = "true")
    SkillSearchRefreshEventRetentionScheduler skillSearchRefreshEventRetentionScheduler(
            SkillSearchRefreshEventStore store,
            com.huawei.skillcenter.persistence.PersistenceBackend persistence,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.retention.retention-days:30}") int retentionDays,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.retention.cleanup-batch-size:1000}") int batchSize,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.retention.cleanup-interval-ms:3600000}") long cleanupIntervalMs,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.retention.cleanup-initial-delay-ms:3600000}") long cleanupInitialDelayMs) {
        requireRetentionSchema(persistence);
        return new SkillSearchRefreshEventRetentionScheduler(store, retentionDays, batchSize, Clock.systemUTC(),
                cleanupIntervalMs, cleanupInitialDelayMs);
    }

    private void requireRetentionSchema(com.huawei.skillcenter.persistence.PersistenceBackend persistence) {
        com.huawei.skillcenter.persistence.PersistenceBackendStatus status = persistence.status();
        if (status == null || !"READY".equals(status.state())
                || !"postgresql".equalsIgnoreCase(status.backendId())
                || !hasSchemaAtLeast(status.schemaVersion(), 19)) {
            throw new IllegalStateException("search refresh retention requires READY PostgreSQL V19 schema");
        }
    }

    private boolean hasSchemaAtLeast(String schemaVersion, int requiredVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) return false;
        try {
            return Integer.parseInt(schemaVersion.trim().split("\\.", 2)[0]) >= requiredVersion;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
