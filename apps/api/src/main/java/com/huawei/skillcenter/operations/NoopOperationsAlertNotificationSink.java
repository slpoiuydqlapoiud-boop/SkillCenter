package com.huawei.skillcenter.operations;

public final class NoopOperationsAlertNotificationSink implements OperationsAlertNotificationSink {
    public static final NoopOperationsAlertNotificationSink INSTANCE = new NoopOperationsAlertNotificationSink();

    private NoopOperationsAlertNotificationSink() {
    }

    @Override
    public void notify(OperationsAlertSnapshot alert) {
        // External notifications are opt-in; local alert APIs remain the source of truth by default.
    }
}
