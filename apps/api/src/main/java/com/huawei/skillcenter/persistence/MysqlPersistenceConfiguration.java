package com.huawei.skillcenter.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Conditional(MysqlPersistenceConfiguration.MysqlBackendCondition.class)
public class MysqlPersistenceConfiguration {
    private static final long MINIMUM_TIMEOUT_MILLIS = 250;
    private static final long MAXIMUM_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MINIMUM_POOL_SIZE = 1;
    private static final int MAXIMUM_POOL_SIZE = 20;
    private static final long MINIMUM_SLO_MILLIS = 1;
    private static final long MAXIMUM_SLO_MILLIS = Duration.ofSeconds(5).toMillis();

    @Bean
    MysqlPersistenceBackend mysqlPersistenceBackend(MysqlPersistenceProperties properties,
                                                    ObjectProvider<DataSource> dataSource,
                                                    ObjectProvider<Flyway> flyway) {
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
        return new MysqlPersistenceBackend(properties, configuredDataSource, configuredFlyway, migrated);
    }

    /**
     * The backend performs the vendor migration before exposing READY state. Most domain stores load their
     * document during construction, so make the migration backend an explicit prerequisite in MySQL mode.
     */
    @Bean
    static BeanFactoryPostProcessor mysqlPersistenceMigrationOrdering(Environment environment) {
        if (!"mysql".equals(PersistenceControlProperties.normalizeBackendValue(
                environment.getProperty("skill-center.persistence.backend", "json")))) {
            return beanFactory -> { };
        }
        return beanFactory -> {
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
                String className = definition.getBeanClassName();
                if (!isDomainBean(className)) continue;
                String[] existing = definition.getDependsOn();
                if (existing == null || java.util.Arrays.stream(existing)
                        .noneMatch("mysqlPersistenceBackend"::equals)) {
                    String[] dependencies = existing == null ? new String[0] : existing;
                    String[] updated = java.util.Arrays.copyOf(dependencies, dependencies.length + 1);
                    updated[dependencies.length] = "mysqlPersistenceBackend";
                    definition.setDependsOn(updated);
                }
            }
        };
    }

    private static boolean isDomainBean(String className) {
        return className != null
                && className.startsWith("com.huawei.skillcenter.")
                && !className.startsWith("com.huawei.skillcenter.persistence.")
                && !className.equals("com.huawei.skillcenter.SkillCenterApiApplication");
    }

    @Bean(destroyMethod = "close")
    @Conditional(ValidMysqlConnectionCondition.class)
    DataSource mysqlDataSource(MysqlPersistenceProperties properties) {
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
    @Conditional(ValidMysqlConnectionCondition.class)
    JdbcTemplate mysqlJdbcTemplate(DataSource mysqlDataSource) {
        return new JdbcTemplate(mysqlDataSource);
    }

    @Bean
    @Conditional(ValidMysqlConnectionCondition.class)
    DataSourceTransactionManager mysqlTransactionManager(DataSource mysqlDataSource) {
        return new DataSourceTransactionManager(mysqlDataSource);
    }

    @Bean
    @Conditional(ValidMysqlConnectionCondition.class)
    Flyway mysqlFlyway(DataSource mysqlDataSource) {
        return Flyway.configure().dataSource(mysqlDataSource)
                .locations("classpath:db/migration-mysql").load();
    }

    static boolean hasValidConnectionConfiguration(MysqlPersistenceProperties properties) {
        if (properties == null) return false;
        Duration timeout = properties.getTimeout();
        return hasText(properties.getUrl()) && properties.getUrl().trim().startsWith("jdbc:mysql:")
                && hasText(properties.getUsername()) && hasText(properties.getPassword())
                && timeout != null && timeoutMillis(properties) >= MINIMUM_TIMEOUT_MILLIS
                && hasValidPoolConfiguration(properties);
    }

    static boolean hasValidPoolConfiguration(MysqlPersistenceProperties properties) {
        if (properties == null) return false;
        return properties.getMaximumPoolSize() >= MINIMUM_POOL_SIZE
                && properties.getMaximumPoolSize() <= MAXIMUM_POOL_SIZE
                && properties.getMinimumIdle() >= 0
                && properties.getMinimumIdle() <= properties.getMaximumPoolSize()
                && hasValidReadinessSlo(properties);
    }

    static boolean hasValidReadinessSlo(MysqlPersistenceProperties properties) {
        if (properties == null || properties.getReadinessSlo() == null) return false;
        try {
            long millis = properties.getReadinessSlo().toMillis();
            return millis >= MINIMUM_SLO_MILLIS && millis <= MAXIMUM_SLO_MILLIS;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private static long timeoutMillis(MysqlPersistenceProperties properties) {
        if (properties == null || properties.getTimeout() == null) return -1;
        try {
            long millis = properties.getTimeout().toMillis();
            return millis >= MINIMUM_TIMEOUT_MILLIS && millis <= MAXIMUM_TIMEOUT_MILLIS ? millis : -1;
        } catch (ArithmeticException ignored) {
            return -1;
        }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    static final class MysqlBackendCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String backend = context.getEnvironment().getProperty("skill-center.persistence.backend", "json");
            return "mysql".equals(PersistenceControlProperties.normalizeBackendValue(backend));
        }
    }

    static final class ValidMysqlConnectionCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            MysqlPersistenceProperties properties = new MysqlPersistenceProperties();
            try {
                var binder = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment());
                binder.bind("skill-center.persistence.mysql", MysqlPersistenceProperties.class)
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
