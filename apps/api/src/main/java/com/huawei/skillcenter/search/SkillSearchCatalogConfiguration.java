package com.huawei.skillcenter.search;

import com.huawei.skillcenter.access.SkillScopeRepository;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.skill.SkillRepository;
import com.huawei.skillcenter.quality.ProviderCredentialResolver;
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
    @Conditional(SkillSearchBackendCondition.Opensearch.class)
    HttpSkillSearchIndex opensearchSkillSearchIndex(
            @org.springframework.beans.factory.annotation.Value("${skill-center.search-index.endpoint:}") String endpoint,
            @org.springframework.beans.factory.annotation.Value("${skill-center.search-index.index:}") String index,
            @org.springframework.beans.factory.annotation.Value("${skill-center.search-index.credential-ref:}") String credentialRef,
            @org.springframework.beans.factory.annotation.Value("${skill-center.search-index.connect-timeout-ms:1000}") long connectTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${skill-center.search-index.request-timeout-ms:5000}") long requestTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${skill-center.search-index.max-response-bytes:256000}") int maxResponseBytes,
            @org.springframework.beans.factory.annotation.Value("${skill-center.search-index.probe-ttl-seconds:300}") long probeTtlSeconds,
            ProviderCredentialResolver credentials, ObjectMapper mapper) {
        return new HttpSkillSearchIndex(endpoint, index, credentialRef,
                java.time.Duration.ofMillis(connectTimeoutMs), java.time.Duration.ofMillis(requestTimeoutMs),
                maxResponseBytes, null, mapper, credentials, Clock.systemUTC(),
                java.time.Duration.ofSeconds(Math.max(1, probeTtlSeconds)));
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
    @Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "skill-center.search-index-events.enabled", havingValue = "true")
    JdbcSkillSearchRefreshEventStore skillSearchRefreshEventStore(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new JdbcSkillSearchRefreshEventStore(jdbc, transactionManager);
    }

    @Bean
    @Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
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
    @Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "skill-center.search-index-events.bus.transport", havingValue = "redis")
    RedisSkillSearchRefreshEventBus skillSearchRefreshEventBus(
            org.springframework.data.redis.core.StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.bus.stream:skill-center:search:refresh}") String stream,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.bus.group:skill-center-search-index}") String group) {
        return new RedisSkillSearchRefreshEventBus(redis, stream, group);
    }

    @Bean
    @Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "skill-center.search-index-events.bus.transport", havingValue = "redis")
    SkillSearchRefreshMessageBusConsumer skillSearchRefreshMessageBusConsumer(
            SkillSearchRefreshEventBus bus,
            SkillSearchRefreshCoordinator coordinator,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.consumer-id:local}") String consumerId,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.bus.batch-size:100}") int batchSize) {
        return new SkillSearchRefreshMessageBusConsumer(bus, coordinator, consumerId, batchSize);
    }

    @Bean
    @Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
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
                    "${skill-center.search-index-events.retention.consumer-stale-after-ms:900000}") long consumerStaleAfterMs,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.retention.cleanup-interval-ms:3600000}") long cleanupIntervalMs,
            @org.springframework.beans.factory.annotation.Value(
                    "${skill-center.search-index-events.retention.cleanup-initial-delay-ms:3600000}") long cleanupInitialDelayMs) {
        requireRetentionSchema(persistence);
        return new SkillSearchRefreshEventRetentionScheduler(store, retentionDays, batchSize, Clock.systemUTC(),
                consumerStaleAfterMs, cleanupIntervalMs, cleanupInitialDelayMs);
    }

    private void requireRetentionSchema(com.huawei.skillcenter.persistence.PersistenceBackend persistence) {
        com.huawei.skillcenter.persistence.PersistenceBackendStatus status = persistence.status();
        if (status == null || !"READY".equals(status.state())
                || !"postgresql".equalsIgnoreCase(status.backendId())
                || !hasSchemaAtLeast(status.schemaVersion(), 20)) {
            throw new IllegalStateException("search refresh retention requires READY PostgreSQL V20 schema");
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
