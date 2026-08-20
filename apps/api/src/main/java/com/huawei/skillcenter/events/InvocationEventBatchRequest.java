package com.huawei.skillcenter.events;

import java.util.List;

public record InvocationEventBatchRequest(String batchId, String schemaVersion, List<InvocationEvent> events) {
}
