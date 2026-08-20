package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.util.Map;

public record AuditSummaryRow(
        String auditId,
        String action,
        String resourceType,
        String resourceId,
        String actorRole,
        String requestId,
        Instant occurredAt,
        Map<String, String> metadata
) {
    public AuditSummaryRow {
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
