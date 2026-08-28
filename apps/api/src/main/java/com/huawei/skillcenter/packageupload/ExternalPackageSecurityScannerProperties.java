package com.huawei.skillcenter.packageupload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Non-secret configuration for the optional external package security scanner. */
@Component
@ConfigurationProperties(prefix = "skill-center.package-security.external")
public class ExternalPackageSecurityScannerProperties {
    private String mode = "disabled";
    private String endpoint = "";
    private String credentialRef = "";
    private int connectTimeoutMs = 1000;
    private int requestTimeoutMs = 10000;
    private int maxResponseBytes = 1024 * 1024;
    private long maxRequestBytes = 20L * 1024 * 1024;
    private Set<String> capabilities = new LinkedHashSet<>();

    public void validate() {
        PackageSecurityExternalMode parsedMode = PackageSecurityExternalMode.parse(mode);
        if (connectTimeoutMs < 100 || connectTimeoutMs > 10000) {
            throw new IllegalArgumentException("external scanner connect timeout is out of range");
        }
        if (requestTimeoutMs < 100 || requestTimeoutMs > 30000) {
            throw new IllegalArgumentException("external scanner request timeout is out of range");
        }
        if (maxResponseBytes < 4096 || maxResponseBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("external scanner response limit is out of range");
        }
        if (maxRequestBytes < 4096 || maxRequestBytes > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("external scanner request limit is out of range");
        }
        if (parsedMode == PackageSecurityExternalMode.DISABLED) return;
        URI uri = parseEndpoint(endpoint);
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("external scanner endpoint contains unsafe URL parts");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("external scanner endpoint host is required");
        }
        boolean loopback = isLoopback(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("external scanner endpoint must use HTTPS outside loopback");
        }
        if (credentialRef == null || !credentialRef.trim().matches("secret://[A-Za-z0-9._:/-]{1,255}")) {
            throw new IllegalArgumentException("external scanner credential reference is invalid");
        }
        Set<String> declared = normalizedCapabilities();
        Set<String> required = Set.of("MALWARE", "SENSITIVE_INFORMATION", "DEPENDENCY_VULNERABILITY", "LICENSE");
        if (!declared.containsAll(required)) {
            throw new IllegalArgumentException("external scanner capabilities are incomplete");
        }
    }

    public boolean required() {
        return PackageSecurityExternalMode.parse(mode) == PackageSecurityExternalMode.REQUIRED;
    }

    public Set<String> normalizedCapabilities() {
        Set<String> values = new LinkedHashSet<>();
        if (capabilities != null) {
            for (String value : capabilities) {
                if (value != null && !value.isBlank()) values.add(value.trim().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(values);
    }

    private URI parseEndpoint(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("external scanner endpoint scheme is invalid");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("external scanner endpoint is invalid");
        }
    }

    private boolean isLoopback(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace("[", "").replace("]", "");
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode == null ? "disabled" : mode; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint; }
    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef == null ? "" : credentialRef; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
    public Set<String> getCapabilities() { return capabilities; }
    public void setCapabilities(Set<String> capabilities) {
        this.capabilities = capabilities == null ? new LinkedHashSet<>() : new LinkedHashSet<>(capabilities);
    }
}
