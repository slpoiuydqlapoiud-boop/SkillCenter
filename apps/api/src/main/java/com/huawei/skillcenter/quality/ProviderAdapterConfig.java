package com.huawei.skillcenter.quality;

import java.net.URI;

/**
 * Non-secret configuration reference for an external provider adapter.
 * The MVP deliberately never stores or sends a credential value.
 */
public record ProviderAdapterConfig(boolean enabled, String endpoint, String credentialRef, String mode) {
    public ProviderAdapterConfig(boolean enabled, String endpoint, String credentialRef) {
        this(enabled, endpoint, credentialRef, "contract");
    }

    public ProviderAdapterConfig {
        endpoint = normalize(endpoint);
        credentialRef = normalize(credentialRef);
        mode = normalize(mode).toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("contract", "http").contains(mode)) {
            throw new IllegalArgumentException("mode must be contract or http");
        }
        if (enabled && !endpoint.isBlank() && !isSafeEndpoint(endpoint)) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URL without credentials, query, or fragment");
        }
        if (containsRawCredential(credentialRef)) {
            throw new IllegalArgumentException("credentialRef must reference a secret, not contain a raw credential");
        }
    }

    public static ProviderAdapterConfig disabled() {
        return new ProviderAdapterConfig(false, "", "");
    }

    public boolean configured() {
        return enabled && !endpoint.isBlank() && credentialRef.startsWith("secret://");
    }

    public boolean httpEnabled() {
        return configured() && "http".equals(mode);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean containsRawCredential(String value) {
        String lower = value.toLowerCase();
        return lower.startsWith("bearer ")
                || lower.startsWith("basic ")
                || lower.startsWith("sk-")
                || lower.contains("token=")
                || lower.contains("password=")
                || lower.contains("secret=");
    }

    private static boolean isSafeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
