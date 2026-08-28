package com.huawei.skillcenter.release;

import java.util.Locale;

public enum ReleaseAdmissionMode {
    LEGACY_COMPATIBLE,
    CONTROLLED;

    public static ReleaseAdmissionMode from(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY_COMPATIBLE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("release admission mode is not supported");
        }
    }
}
