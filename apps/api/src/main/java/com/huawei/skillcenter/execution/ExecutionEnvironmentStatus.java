package com.huawei.skillcenter.execution;

import java.util.Locale;

public enum ExecutionEnvironmentStatus {
    ACTIVE,
    DEGRADED,
    DISABLED;

    public static ExecutionEnvironmentStatus from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status must be ACTIVE, DEGRADED or DISABLED");
        }
    }
}
