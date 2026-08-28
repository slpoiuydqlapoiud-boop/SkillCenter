package com.huawei.skillcenter.operations;

import java.util.List;
import java.util.UUID;

public record RuntimeSummaryBatchResponse(
        String batchId,
        int accepted,
        int duplicates,
        int rejected,
        List<BatchResult> results
) {
    public RuntimeSummaryBatchResponse {
        results = List.copyOf(results == null ? List.of() : results);
    }

    public record BatchResult(UUID eventId, boolean accepted, boolean duplicate, String errorCode) {
    }
}
