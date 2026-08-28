package com.huawei.skillcenter.quality;

public enum CompatibilityMatrixPolicy {
    ALL_MUST_PASS,
    MIN_PASS_RATE;

    public static CompatibilityMatrixPolicy from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("policy is required");
        }
        try {
            return value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_')
                    .transform(CompatibilityMatrixPolicy::valueOf);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("policy must be ALL_MUST_PASS or MIN_PASS_RATE");
        }
    }
}
