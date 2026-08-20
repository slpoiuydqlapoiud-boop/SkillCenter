package com.huawei.skillcenter.analytics;

public class InvalidAnalyticsQueryException extends IllegalArgumentException {
    public InvalidAnalyticsQueryException(String message) {
        super(message);
    }

    public InvalidAnalyticsQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
