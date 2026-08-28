package com.huawei.skillcenter.operations;

import java.time.Instant;

/** Persisted state metadata for one operations alert key. */
public record OperationsAlertState(boolean active, Instant firstTriggeredAt, Instant lastEvaluatedAt) {
    public OperationsAlertState {
        if (lastEvaluatedAt == null) {
            throw new IllegalArgumentException("lastEvaluatedAt is required");
        }
        if (active && firstTriggeredAt == null) {
            throw new IllegalArgumentException("firstTriggeredAt is required for active alerts");
        }
    }
}
