package com.huawei.skillcenter.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
public record InvocationEvent(
        String schemaVersion,
        UUID eventId,
        OffsetDateTime occurredAt,
        String skillId,
        String version,
        Subject subject,
        Client client,
        String sessionId,
        String status,
        long durationMs,
        String errorCode,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Subject(String userId, String teamId) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Client(String type, String version) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Usage(String model, long inputTokens, long outputTokens) {}
}
