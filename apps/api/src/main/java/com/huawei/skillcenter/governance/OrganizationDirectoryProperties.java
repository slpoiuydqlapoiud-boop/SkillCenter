package com.huawei.skillcenter.governance;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@ConfigurationProperties(prefix = "skill-center.governance.organization-directory")
public class OrganizationDirectoryProperties {
    private String mode = "local";
    private String endpoint = "";
    private String credentialRef = "";
    private int connectTimeoutMs = 1_000;
    private int requestTimeoutMs = 2_000;
    private int maxResponseBytes = 1_048_576;
    private long maxAgeSeconds = 900;
    private String storage = "./data/governance/organization-directory.json";

    @PostConstruct
    void validateOnStartup() {
        validate();
    }

    public void validate() {
        mode = mode == null || mode.isBlank() ? "local" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("local") && !mode.equals("http")) {
            throw new IllegalArgumentException("organization directory mode is invalid");
        }
        if (connectTimeoutMs < 100 || connectTimeoutMs > 10_000
                || requestTimeoutMs < 100 || requestTimeoutMs > 15_000) {
            throw new IllegalArgumentException("organization directory timeout is invalid");
        }
        if (maxResponseBytes < 4_096 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("organization directory response size is invalid");
        }
        if (maxAgeSeconds < 30 || maxAgeSeconds > 86_400) {
            throw new IllegalArgumentException("organization directory max age is invalid");
        }
        if (storage == null || storage.isBlank() || storage.length() > 512) {
            throw new IllegalArgumentException("organization directory storage is invalid");
        }
        if (mode.equals("http")) {
            validateEndpoint(endpoint);
            if (credentialRef == null || !credentialRef.trim().matches("secret://[A-Za-z0-9._:/-]{1,255}")) {
                throw new IllegalArgumentException("organization directory credential reference is invalid");
            }
        }
    }

    private void validateEndpoint(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("organization directory endpoint is invalid");
            }
            if (scheme.equals("http") && !isLoopback(uri.getHost())) {
                throw new IllegalArgumentException("organization directory endpoint must use HTTPS");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("organization directory endpoint is invalid", exception);
        }
    }

    private boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint.trim(); }
    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef == null ? "" : credentialRef.trim(); }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public long getMaxAgeSeconds() { return maxAgeSeconds; }
    public void setMaxAgeSeconds(long maxAgeSeconds) { this.maxAgeSeconds = maxAgeSeconds; }
    public String getStorage() { return storage; }
    public void setStorage(String storage) { this.storage = storage; }
}
