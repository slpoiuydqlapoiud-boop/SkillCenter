package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String auditId,
        String action,
        String resourceType,
        String resourceId,
        String actorId,
        String actorRole,
        String requestId,
        Instant occurredAt,
        Map<String, String> metadata
) {
}
