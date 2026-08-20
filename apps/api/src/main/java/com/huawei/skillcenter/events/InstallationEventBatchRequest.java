package com.huawei.skillcenter.events;

import java.util.List;

public record InstallationEventBatchRequest(String batchId, String schemaVersion, List<InstallationEvent> events) {
}
