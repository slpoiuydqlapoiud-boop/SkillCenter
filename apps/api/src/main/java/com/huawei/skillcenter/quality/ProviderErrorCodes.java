package com.huawei.skillcenter.quality;

/**
 * Keeps Provider and evaluation failures as bounded identifiers. Provider responses must never turn
 * free-form upstream text into persisted quality evidence or operational dimensions.
 */
public final class ProviderErrorCodes {
    private static final String FALLBACK = "PROVIDER_ERROR";
    private static final int MAX_LENGTH = 63;

    private ProviderErrorCodes() {
    }

    public static String normalize(String value, String fallback) {
        String safeFallback = isStable(fallback) ? fallback.trim() : FALLBACK;
        if (value == null) {
            return safeFallback;
        }
        String normalized = value.trim();
        return isStable(normalized) ? normalized : safeFallback;
    }

    public static boolean isStable(String value) {
        return value != null
                && value.length() >= 3
                && value.length() <= MAX_LENGTH
                && value.matches("[A-Z][A-Z0-9_]{2,62}");
    }
}
