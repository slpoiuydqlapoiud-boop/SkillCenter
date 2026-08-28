package com.huawei.skillcenter.operations;

/** Result of an atomic alert state transition. */
public record OperationsAlertStateTransition(
        OperationsAlertState previous,
        OperationsAlertState current,
        boolean transitioned) {
    public OperationsAlertStateTransition {
        if (current == null) {
            throw new IllegalArgumentException("current is required");
        }
    }
}
