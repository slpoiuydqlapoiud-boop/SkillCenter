package com.huawei.skillcenter.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
public record InstallationEvent(
        String schemaVersion,
        UUID eventId,
        OffsetDateTime occurredAt,
        String skillId,
        String version,
        String fromVersion,
        Subject subject,
        Client client,
        String deviceId,
        String action,
        String method,
        String outcome,
        String errorCode
) {
    /**
     * Compatibility constructor for callers that only create install/uninstall events.
     * Upgrade and downgrade events should use the canonical constructor with fromVersion.
     */
    public InstallationEvent(String schemaVersion, UUID eventId, OffsetDateTime occurredAt, String skillId,
                             String version, Subject subject, Client client, String deviceId, String action,
                             String method, String outcome, String errorCode) {
        this(schemaVersion, eventId, occurredAt, skillId, version, null, subject, client, deviceId, action, method,
                outcome, errorCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Subject(String userId, String teamId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Client(String type, String version) {
    }
}
