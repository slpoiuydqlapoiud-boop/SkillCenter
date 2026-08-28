package com.huawei.skillcenter.release;

import java.util.Locale;

public enum ReleaseEnvironment {
    STAGING,
    PRODUCTION;

    public static ReleaseEnvironment from(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("targetEnvironment is required");
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("targetEnvironment must be STAGING or PRODUCTION", exception);
        }
    }
}
