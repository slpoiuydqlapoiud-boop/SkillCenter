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
        Subject subject,
        Client client,
        String deviceId,
        String action,
        String method,
        String outcome,
        String errorCode
) {
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Subject(String userId, String teamId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Client(String type, String version) {
    }
}
