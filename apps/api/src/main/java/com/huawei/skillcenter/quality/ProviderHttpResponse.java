package com.huawei.skillcenter.quality;

/** Status and body returned by a provider transport; adapters own response parsing. */
public record ProviderHttpResponse(int statusCode, String body) {
    private static final int MAX_BODY_CHARACTERS = 256_000;

    public ProviderHttpResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be an HTTP status");
        }
        body = body == null ? "" : body;
        if (body.length() > MAX_BODY_CHARACTERS) {
            throw new IllegalArgumentException("body must not exceed 256000 characters");
        }
    }

    @Override
    public String toString() {
        return "ProviderHttpResponse[statusCode=" + statusCode + ", body=<redacted>]";
    }
}
