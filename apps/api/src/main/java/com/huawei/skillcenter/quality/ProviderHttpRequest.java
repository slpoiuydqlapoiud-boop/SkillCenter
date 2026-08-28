package com.huawei.skillcenter.quality;

import java.net.URI;
import java.time.Duration;

/** Immutable, redacted request envelope for external provider calls. */
public record ProviderHttpRequest(
        String providerId,
        URI endpoint,
        String bearerCredential,
        String body,
        Duration timeout
) {
    public ProviderHttpRequest {
        providerId = required(providerId, "providerId");
        if (endpoint == null || !isSafeEndpoint(endpoint)) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URL without credentials, query, or fragment");
        }
        bearerCredential = required(bearerCredential, "bearerCredential");
        body = body == null ? "" : body;
        if (body.length() > 256_000) {
            throw new IllegalArgumentException("body must not exceed 256000 characters");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1ms and 120s");
        }
    }

    @Override
    public String toString() {
        return "ProviderHttpRequest[providerId=" + providerId
                + ", endpoint=" + endpoint.getScheme() + "://" + endpoint.getHost()
                + ", bearerCredential=<redacted>, body=<redacted>, timeout=" + timeout + "]";
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static boolean isSafeEndpoint(URI value) {
        String scheme = value.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && value.getHost() != null
                && value.getUserInfo() == null
                && value.getQuery() == null
                && value.getFragment() == null;
    }
}
