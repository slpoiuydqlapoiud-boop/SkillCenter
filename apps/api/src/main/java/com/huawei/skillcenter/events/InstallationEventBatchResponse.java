package com.huawei.skillcenter.events;

import java.util.List;

public record InstallationEventBatchResponse(
        String batchId,
        int accepted,
        int duplicates,
        int rejected,
        List<InstallationEventService.EventResult> results
) {
}
