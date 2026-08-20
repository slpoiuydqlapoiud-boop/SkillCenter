package com.huawei.skillcenter.events;

import java.util.List;
import java.util.UUID;

public record InvocationEventBatchResponse(
        String batchId,
        int accepted,
        int duplicates,
        int rejected,
        List<BatchResult> results
) {
    public record BatchResult(UUID eventId, boolean accepted, boolean duplicate, String errorCode) {
    }
}
