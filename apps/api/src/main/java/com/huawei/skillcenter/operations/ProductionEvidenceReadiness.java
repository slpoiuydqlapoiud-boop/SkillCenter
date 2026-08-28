package com.huawei.skillcenter.operations;

import java.util.List;

/** Safe aggregate used by the platform readiness gate. */
public record ProductionEvidenceReadiness(
        String status,
        String reasonCode,
        int requiredCount,
        int acceptedCount,
        List<String> blockingReasonCodes) {
    public ProductionEvidenceReadiness {
        status = status == null || status.isBlank() ? "NOT_READY" : status.trim();
        reasonCode = reasonCode == null ? "PRODUCTION_EVIDENCE_UNKNOWN" : reasonCode.trim();
        requiredCount = Math.max(0, requiredCount);
        acceptedCount = Math.max(0, acceptedCount);
        blockingReasonCodes = blockingReasonCodes == null ? List.of() : blockingReasonCodes.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().sorted().toList();
    }
}
