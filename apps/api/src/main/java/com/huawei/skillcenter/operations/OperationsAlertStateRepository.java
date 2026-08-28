package com.huawei.skillcenter.operations;

import java.time.Instant;

public interface OperationsAlertStateRepository extends OperationsAlertStateHealth {
    OperationsAlertStateTransition transition(String key, boolean active, Instant evaluatedAt);
}
