package com.huawei.skillcenter.operations;

@FunctionalInterface
public interface OperationsAlertNotificationSink {
    void notify(OperationsAlertSnapshot alert);
}
