package com.huawei.skillcenter.distribution;

import java.net.URI;

/** Non-secret S3-compatible storage configuration. */
public record ObjectStorageConfig(
        String endpoint,
        String bucket,
        String region,
        String prefix,
        String accessKeyIdRef,
        String secretAccessKeyRef) {

    public ObjectStorageConfig {
        endpoint = normalize(endpoint);
        bucket = normalize(bucket);
        region = normalize(region);
        prefix = normalizePrefix(prefix);
        accessKeyIdRef = normalize(accessKeyIdRef);
        secretAccessKeyRef = normalize(secretAccessKeyRef);
        if (!bucket.isBlank() && !bucket.matches("[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("bucket contains unsafe path characters");
        }
    }

    public boolean configured() {
        return safeEndpoint(endpoint) && !bucket.isBlank() && !region.isBlank()
                && isSecretReference(accessKeyIdRef) && isSecretReference(secretAccessKeyRef);
    }

    public String objectKey(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sha256 must be a hexadecimal digest");
        }
        return (prefix.isBlank() ? "" : prefix + "/") + "sha256/" + sha256.toLowerCase() + ".zip";
    }

    private static boolean isSecretReference(String value) {
        return value != null && value.startsWith("secret://") && value.length() > "secret://".length();
    }

    private static boolean safeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", "");
    }

    private static String normalizePrefix(String value) {
        String normalized = normalize(value).replaceAll("^/+|/+$", "");
        if (normalized.contains("..") || !normalized.matches("[A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException("prefix contains unsafe path characters");
        }
        return normalized;
    }
}
