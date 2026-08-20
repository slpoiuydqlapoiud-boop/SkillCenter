package com.huawei.skillcenter.governance;

import java.time.Instant;

public record InstallationRecord(
        String installationId,
        String manifestId,
        String skillId,
        String version,
        String clientType,
        String clientVersion,
        String requestedBy,
        String status,
        Instant requestedAt,
        Instant updatedAt,
        String teamId,
        String deviceId,
        String method,
        String lastEventId,
        String lastErrorCode,
        Instant installedAt,
        Instant removedAt
) {
    public InstallationRecord(String installationId, String manifestId, String skillId, String version,
                               String clientType, String clientVersion, String requestedBy, String status,
                               Instant requestedAt, Instant updatedAt) {
        this(installationId, manifestId, skillId, version, clientType, clientVersion, requestedBy, status,
                requestedAt, updatedAt, null, null, null, null, null, null, null);
    }
}
