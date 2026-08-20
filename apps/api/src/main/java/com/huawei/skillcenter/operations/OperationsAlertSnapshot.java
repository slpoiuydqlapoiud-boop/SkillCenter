package com.huawei.skillcenter.operations;

import java.time.Instant;

public record OperationsAlertSnapshot(
        String rule,
        String eventCode,
        String status,
        double currentValue,
        double threshold,
        String unit,
        Instant firstTriggeredAt,
        Instant lastEvaluatedAt) {
}
