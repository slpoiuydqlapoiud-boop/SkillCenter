package com.huawei.skillcenter.operations;

import java.util.List;

public record RuntimeSummaryBatchRequest(String batchId, String schemaVersion, List<RuntimeSummary> events) {
}
