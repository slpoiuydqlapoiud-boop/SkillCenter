package com.huawei.skillcenter.operations;

import java.time.Instant;

public record ProductionEvidenceUpdateRequest(
        String status,
        String ownerUserId,
        Instant expiresAt,
        String evidenceRef,
        String summary,
        Integer expectedRevision) {
}
