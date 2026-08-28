package com.huawei.skillcenter.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresPersistenceBackendTest {
    @Test
    void postgresDefaultsExposeBoundedPoolAndReadinessSlo() {
        PostgresPersistenceProperties properties = new PostgresPersistenceProperties();

        assertThat(properties.getMinimumIdle()).isEqualTo(2);
        assertThat(properties.getMaximumPoolSize()).isEqualTo(10);
        assertThat(properties.getReadinessSlo()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void invalidPoolBoundsFailConnectionConfigurationValidation() {
        PostgresPersistenceProperties properties = validProperties();
        properties.setMinimumIdle(11);
        properties.setMaximumPoolSize(10);

        assertThat(PostgresPersistenceConfiguration.hasValidConnectionConfiguration(properties)).isFalse();
    }

    @Test
    void healthyDatabaseProbeReportsReady() throws Exception {
        PostgresPersistenceProperties properties = validProperties();
        properties.setReadinessSlo(Duration.ofSeconds(1));
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);

        PostgresPersistenceBackend backend = backend(properties, connection);

        assertThat(backend.status()).isEqualTo(PersistenceBackendStatus.ready("postgresql", "10", null));
    }

    @Test
    void slowDatabaseProbeFailsClosedWithStableSloReason() throws Exception {
        PostgresPersistenceProperties properties = validProperties();
        properties.setReadinessSlo(Duration.ofMillis(1));
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenAnswer(invocation -> {
            Thread.sleep(15);
            return true;
        });

        PostgresPersistenceBackend backend = backend(properties, connection);

        assertThat(backend.status()).isEqualTo(PersistenceBackendStatus.failClosed(
                "postgresql", "PERSISTENCE_DATABASE_SLO_BREACH", "10", null));
    }

    private PostgresPersistenceBackend backend(PostgresPersistenceProperties properties,
                                               Connection connection) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        MigrationInfo migration = mock(MigrationInfo.class);
        when(flyway.info()).thenReturn(info);
        when(info.current()).thenReturn(migration);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("10"));
        return new PostgresPersistenceBackend(properties, dataSource, flyway, true);
    }

    private PostgresPersistenceProperties validProperties() {
        PostgresPersistenceProperties properties = new PostgresPersistenceProperties();
        properties.setUrl("jdbc:postgresql://localhost/skill_center");
        properties.setUsername("skill-center");
        properties.setPassword("secret");
        properties.setTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
