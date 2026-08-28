package com.huawei.skillcenter.packageupload;

import java.util.Locale;

public enum PackageSecurityExternalMode {
    DISABLED,
    REQUIRED;

    public static PackageSecurityExternalMode parse(String value) {
        if (value == null || value.isBlank()) return DISABLED;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "DISABLED" -> DISABLED;
            case "REQUIRED" -> REQUIRED;
            default -> throw new IllegalArgumentException("Unsupported package security external mode");
        };
    }
}
