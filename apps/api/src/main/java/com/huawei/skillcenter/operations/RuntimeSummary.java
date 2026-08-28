package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A deliberately small, redacted execution summary. It must never contain
 * prompts, inputs, outputs, tool arguments, credentials, or user identity.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RuntimeSummary(
        String schemaVersion,
        UUID eventId,
        OffsetDateTime occurredAt,
        String skillId,
        String version,
        String status,
        long durationMs,
        String errorCode,
        String dataSource,
        String teamId,
        String clientType,
        String traceRef,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    public RuntimeSummary(String schemaVersion, UUID eventId, OffsetDateTime occurredAt, String skillId,
                          String version, String status, long durationMs, String errorCode, String dataSource,
                          String teamId, String clientType, String traceRef) {
        this(schemaVersion, eventId, occurredAt, skillId, version, status, durationMs, errorCode, dataSource,
                teamId, clientType, traceRef, "", "", "");
    }

    public RuntimeSummary {
        runtimeId = environmentId(runtimeId);
        mcpServerId = environmentId(mcpServerId);
        llmProviderId = environmentId(llmProviderId);
    }

    private static String environmentId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("execution environment identifier is invalid");
        }
        return normalized;
    }
}
