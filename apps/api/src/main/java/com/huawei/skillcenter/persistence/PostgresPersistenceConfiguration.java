package com.huawei.skillcenter.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Conditional(PostgresPersistenceConfiguration.PostgresBackendCondition.class)
public class PostgresPersistenceConfiguration {
    private static final long MINIMUM_TIMEOUT_MILLIS = 250;
    private static final long MAXIMUM_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MINIMUM_POOL_SIZE = 1;
    private static final int MAXIMUM_POOL_SIZE = 100;
    private static final long MINIMUM_SLO_MILLIS = 1;
    private static final long MAXIMUM_SLO_MILLIS = Duration.ofSeconds(5).toMillis();

    @Bean
    PostgresPersistenceBackend postgresPersistenceBackend(PostgresPersistenceProperties properties,
                                                           org.springframework.beans.factory.ObjectProvider<DataSource> dataSource,
                                                           org.springframework.beans.factory.ObjectProvider<Flyway> flyway) {
        DataSource configuredDataSource = dataSource.getIfAvailable();
        Flyway configuredFlyway = flyway.getIfAvailable();
        boolean migrated = false;
        if (configuredDataSource != null && configuredFlyway != null) {
            try {
                configuredFlyway.migrate();
                migrated = true;
            } catch (Exception ignored) {
                migrated = false;
            }
        }
        return new PostgresPersistenceBackend(properties, configuredDataSource, configuredFlyway, migrated);
    }

    @Bean(destroyMethod = "close")
    @Conditional(ValidPostgresConnectionCondition.class)
    DataSource postgresDataSource(PostgresPersistenceProperties properties) {
        long timeoutMillis = timeoutMillis(properties);
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(properties.getUrl().trim());
        configuration.setUsername(properties.getUsername().trim());
        configuration.setPassword(properties.getPassword());
        configuration.setConnectionTimeout(timeoutMillis);
        configuration.setValidationTimeout(timeoutMillis);
        configuration.setInitializationFailTimeout(-1);
        configuration.setMinimumIdle(properties.getMinimumIdle());
        configuration.setMaximumPoolSize(properties.getMaximumPoolSize());
        return new HikariDataSource(configuration);
    }

    @Bean
    @Conditional(ValidPostgresConnectionCondition.class)
    JdbcTemplate postgresJdbcTemplate(DataSource postgresDataSource) {
        return new JdbcTemplate(postgresDataSource);
    }

    @Bean
    @Conditional(ValidPostgresConnectionCondition.class)
    DataSourceTransactionManager postgresTransactionManager(DataSource postgresDataSource) {
        return new DataSourceTransactionManager(postgresDataSource);
    }

    @Bean
    @Conditional(ValidPostgresConnectionCondition.class)
    Flyway postgresFlyway(DataSource postgresDataSource) {
        return Flyway.configure()
                .dataSource(postgresDataSource)
                .locations("classpath:db/migration")
                .load();
    }

    static boolean hasValidConnectionConfiguration(PostgresPersistenceProperties properties) {
        if (properties == null) {
            return false;
        }
        Duration timeout = properties.getTimeout();
        return hasText(properties.getUrl())
                && properties.getUrl().trim().startsWith("jdbc:postgresql:")
                && hasText(properties.getUsername())
                && hasText(properties.getPassword())
                && timeout != null
                && timeoutMillis(properties) >= MINIMUM_TIMEOUT_MILLIS
                && hasValidPoolConfiguration(properties);
    }

    static boolean hasValidPoolConfiguration(PostgresPersistenceProperties properties) {
        if (properties == null) return false;
        return properties.getMaximumPoolSize() >= MINIMUM_POOL_SIZE
                && properties.getMaximumPoolSize() <= MAXIMUM_POOL_SIZE
                && properties.getMinimumIdle() >= 0
                && properties.getMinimumIdle() <= properties.getMaximumPoolSize()
                && hasValidReadinessSlo(properties);
    }

    static boolean hasValidReadinessSlo(PostgresPersistenceProperties properties) {
        if (properties == null || properties.getReadinessSlo() == null) return false;
        try {
            long millis = properties.getReadinessSlo().toMillis();
            return millis >= MINIMUM_SLO_MILLIS && millis <= MAXIMUM_SLO_MILLIS;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private static long timeoutMillis(PostgresPersistenceProperties properties) {
        if (properties == null || properties.getTimeout() == null) {
            return -1;
        }
        try {
            long millis = properties.getTimeout().toMillis();
            return millis >= MINIMUM_TIMEOUT_MILLIS && millis <= MAXIMUM_TIMEOUT_MILLIS ? millis : -1;
        } catch (ArithmeticException ignored) {
            return -1;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static final class PostgresBackendCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String backend = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
            return "postgresql".equals(PersistenceControlProperties.normalizeBackendValue(backend));
        }
    }

    static final class ValidPostgresConnectionCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            PostgresPersistenceProperties properties = new PostgresPersistenceProperties();
            try {
                var binder = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment());
                binder.bind("skill-center.persistence.postgresql", PostgresPersistenceProperties.class)
                        .ifBound(bound -> {
                            properties.setUrl(bound.getUrl());
                            properties.setUsername(bound.getUsername());
                            properties.setPassword(bound.getPassword());
                            properties.setTimeout(bound.getTimeout());
                            properties.setMinimumIdle(bound.getMinimumIdle());
                            properties.setMaximumPoolSize(bound.getMaximumPoolSize());
                            properties.setReadinessSlo(bound.getReadinessSlo());
                        });
                return hasValidConnectionConfiguration(properties);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }
}
