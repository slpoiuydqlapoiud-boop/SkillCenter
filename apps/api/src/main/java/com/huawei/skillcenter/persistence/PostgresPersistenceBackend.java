package com.huawei.skillcenter.persistence;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;

public final class PostgresPersistenceBackend implements PersistenceBackend {
    private static final String CONTROL_PLANE_ERROR = "PERSISTENCE_CONTROL_PLANE_ERROR";

    private final PostgresPersistenceProperties properties;
    private final DataSource dataSource;
    private final Flyway flyway;
    private final boolean migrated;

    PostgresPersistenceBackend(PostgresPersistenceProperties properties, DataSource dataSource,
                               Flyway flyway, boolean migrated) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.migrated = migrated;
    }

    @Override
    public String backendId() {
        return "postgresql";
    }

    @Override
    public PersistenceBackendStatus status() {
        if (!PostgresPersistenceConfiguration.hasValidPoolConfiguration(properties)) {
            return failure("PERSISTENCE_POOL_CONFIGURATION_INVALID");
        }
        if (!PostgresPersistenceConfiguration.hasValidConnectionConfiguration(properties)
                || dataSource == null || flyway == null || !migrated) {
            return failure();
        }
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            int timeoutSeconds = Math.max(1, (int) properties.getTimeout().toSeconds());
            if (!connection.isValid(timeoutSeconds)) {
                return failure();
            }
            var current = flyway.info().current();
            if (current == null || current.getVersion() == null) {
                return failure();
            }
            long elapsedNanos = System.nanoTime() - started;
            if (elapsedNanos > properties.getReadinessSlo().toNanos()) {
                return PersistenceBackendStatus.failClosed(backendId(),
                        "PERSISTENCE_DATABASE_SLO_BREACH", current.getVersion().getVersion(), null);
            }
            return PersistenceBackendStatus.ready(backendId(), current.getVersion().getVersion(), null);
        } catch (Exception ignored) {
            return failure();
        }
    }

    private PersistenceBackendStatus failure() {
        return failure(CONTROL_PLANE_ERROR);
    }

    private PersistenceBackendStatus failure(String reasonCode) {
        return PersistenceBackendStatus.failClosed(backendId(), reasonCode, null, null);
    }
}
