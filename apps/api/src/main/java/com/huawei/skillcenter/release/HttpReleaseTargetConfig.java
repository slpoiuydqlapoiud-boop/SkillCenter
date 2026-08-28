package com.huawei.skillcenter.release;

import java.net.URI;
import java.time.Duration;

/** Non-secret configuration for the explicit HTTP release target adapter. */
public record HttpReleaseTargetConfig(String endpoint, String credentialRef, Duration timeout) {
    public HttpReleaseTargetConfig {
        endpoint = endpoint == null ? "" : endpoint.trim();
        credentialRef = credentialRef == null ? "" : credentialRef.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (!endpoint.isBlank() && !isSafeEndpoint(endpoint)) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URL without credentials, query, or fragment");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1ms and 120s");
        }
        if (!credentialRef.isBlank() && !credentialRef.startsWith("secret://")) {
            throw new IllegalArgumentException("credentialRef must reference a secret");
        }
        String lower = credentialRef.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("bearer ") || lower.startsWith("basic ") || lower.startsWith("sk-")
                || lower.contains("token=") || lower.contains("password=") || lower.contains("secret=")) {
            throw new IllegalArgumentException("credentialRef must not contain a raw credential");
        }
    }

    private static boolean isSafeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
