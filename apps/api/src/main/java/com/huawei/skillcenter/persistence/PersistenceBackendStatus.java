package com.huawei.skillcenter.persistence;

import java.util.Locale;
import java.util.Set;

public record PersistenceBackendStatus(
        String backendId,
        String state,
        String reasonCode,
        String schemaVersion,
        Long revision) {
    private static final String READY = "READY";
    private static final String FAIL_CLOSED = "FAIL_CLOSED";
    private static final String CONTROL_PLANE_ERROR = "PERSISTENCE_CONTROL_PLANE_ERROR";
    private static final Set<String> BACKENDS = Set.of("json", "postgresql");
    private static final Set<String> STABLE_REASON_CODES = Set.of(
            CONTROL_PLANE_ERROR,
            "PERSISTENCE_POOL_CONFIGURATION_INVALID",
            "PERSISTENCE_DATABASE_SLO_BREACH");

    public PersistenceBackendStatus {
        backendId = BACKENDS.contains(normalize(backendId)) ? normalize(backendId) : "unknown";
        state = READY.equals(state) ? READY : FAIL_CLOSED;
        reasonCode = READY.equals(state) ? null : safeReasonCode(reasonCode);
        schemaVersion = safeSchemaVersion(schemaVersion);
    }

    public static PersistenceBackendStatus ready(String backendId, String schemaVersion, Long revision) {
        return new PersistenceBackendStatus(backendId, READY, null, schemaVersion, revision);
    }

    public static PersistenceBackendStatus failClosed(String backendId, String reasonCode,
                                                      String schemaVersion, Long revision) {
        return new PersistenceBackendStatus(backendId, FAIL_CLOSED, reasonCode, schemaVersion, revision);
    }

    @Override
    public String toString() {
        return "PersistenceBackendStatus[backendId=" + backendId
                + ", state=" + state
                + ", reasonCode=" + reasonCode
                + ", schemaVersion=" + schemaVersion
                + ", revision=" + revision + "]";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeReasonCode(String value) {
        return STABLE_REASON_CODES.contains(value) ? value : CONTROL_PLANE_ERROR;
    }

    private static String safeSchemaVersion(String value) {
        return value != null && value.matches("[0-9]+(?:\\.[0-9]+)*") ? value : null;
    }
}
