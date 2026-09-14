package com.huawei.skillcenter.persistence;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;

public final class MysqlPersistenceBackend implements PersistenceBackend {
    private final MysqlPersistenceProperties properties;
    private final DataSource dataSource;
    private final Flyway flyway;
    private final boolean migrated;

    MysqlPersistenceBackend(MysqlPersistenceProperties properties, DataSource dataSource,
                            Flyway flyway, boolean migrated) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.migrated = migrated;
    }

    @Override
    public String backendId() {
        return "mysql";
    }

    @Override
    public PersistenceBackendStatus status() {
        if (!MysqlPersistenceConfiguration.hasValidPoolConfiguration(properties)) {
            return failure("PERSISTENCE_POOL_CONFIGURATION_INVALID");
        }
        if (!MysqlPersistenceConfiguration.hasValidConnectionConfiguration(properties)
                || dataSource == null || flyway == null || !migrated) {
            return failure();
        }
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            int timeoutSeconds = Math.max(1, (int) properties.getTimeout().toSeconds());
            if (!connection.isValid(timeoutSeconds)) return failure();
            var current = flyway.info().current();
            if (current == null || current.getVersion() == null) return failure();
            if (System.nanoTime() - started > properties.getReadinessSlo().toNanos()) {
                return PersistenceBackendStatus.failClosed(backendId(),
                        "PERSISTENCE_DATABASE_SLO_BREACH", current.getVersion().getVersion(), null);
            }
            return PersistenceBackendStatus.ready(backendId(), current.getVersion().getVersion(), null);
        } catch (Exception ignored) {
            return failure();
        }
    }

    private PersistenceBackendStatus failure() {
        return failure("PERSISTENCE_CONTROL_PLANE_ERROR");
    }

    private PersistenceBackendStatus failure(String reasonCode) {
        return PersistenceBackendStatus.failClosed(backendId(), reasonCode, null, null);
    }
}
