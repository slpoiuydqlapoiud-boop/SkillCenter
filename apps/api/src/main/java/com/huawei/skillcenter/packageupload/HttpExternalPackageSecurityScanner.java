package com.huawei.skillcenter.packageupload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.ProviderCredentialResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded HTTP adapter for an approved external package security scanner. */
public final class HttpExternalPackageSecurityScanner implements ExternalPackageSecurityScanner {
    private static final String SCHEMA = "package-security-scan.v1";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "status", "scannerId", "scannerVersion", "capabilities", "findings");
    private static final Set<String> FINDING_FIELDS = Set.of("code", "path", "severity");
    private static final Set<String> STATUSES = Set.of("PASSED", "BLOCKED", "NOT_SCANNED");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private final ExternalPackageSecurityScannerProperties properties;
    private final ProviderCredentialResolver credentials;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpExternalPackageSecurityScanner(ExternalPackageSecurityScannerProperties properties,
                                              ProviderCredentialResolver credentials,
                                              HttpClient client,
                                              ObjectMapper mapper) {
        if (properties == null || credentials == null || client == null || mapper == null) {
            throw new IllegalArgumentException("external package scanner dependencies are invalid");
        }
        properties.validate();
        this.properties = properties;
        this.credentials = credentials;
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String scannerId() {
        return "http-external-package-security";
    }

    @Override
    public String scannerVersion() {
        return "contract-v1";
    }

    @Override
    public ExternalPackageSecurityScannerHealth health() {
        if (!properties.required()) return ExternalPackageSecurityScannerHealth.notConfigured();
        return new ExternalPackageSecurityScannerHealth("READY", "EXTERNAL_SECURITY_SCANNER_CONFIGURED");
    }

    @Override
    public Set<PackageSecurityScanCapability> capabilities() {
        EnumSet<PackageSecurityScanCapability> capabilities = EnumSet.noneOf(PackageSecurityScanCapability.class);
        for (String value : properties.normalizedCapabilities()) {
            capabilities.add(PackageSecurityScanCapability.valueOf(value));
        }
        return Set.copyOf(capabilities);
    }

    @Override
    public PackageSecurityScanResult scan(Path zipPath) {
        if (!properties.required()) throw failure("EXTERNAL_SECURITY_SCANNER_NOT_CONFIGURED");
        if (zipPath == null || !Files.isRegularFile(zipPath)) throw failure("EXTERNAL_SECURITY_PACKAGE_INVALID");
        long size;
        try {
            size = Files.size(zipPath);
        } catch (IOException exception) {
            throw failure("EXTERNAL_SECURITY_PACKAGE_UNREADABLE");
        }
        if (size > properties.getMaxRequestBytes()) throw failure("EXTERNAL_SECURITY_PACKAGE_TOO_LARGE");

        String token;
        try {
            token = credentials.resolve(properties.getCredentialRef());
        } catch (RuntimeException exception) {
            throw failure("EXTERNAL_SECURITY_SCANNER_CREDENTIAL_UNAVAILABLE");
        }
        if (token == null || token.isBlank()) throw failure("EXTERNAL_SECURITY_SCANNER_CREDENTIAL_UNAVAILABLE");

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/zip")
                    .header("X-Skill-Package-Sha256", sha256(zipPath))
                    .POST(HttpRequest.BodyPublishers.ofFile(zipPath))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                close(response.body());
                throw failure("EXTERNAL_SECURITY_SCANNER_HTTP_STATUS");
            }
            byte[] body = readBounded(response.body(), properties.getMaxResponseBytes());
            return parse(body);
        } catch (ScannerFailure failure) {
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("EXTERNAL_SECURITY_SCANNER_INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            throw failure("EXTERNAL_SECURITY_SCANNER_UNAVAILABLE");
        }
    }

    private PackageSecurityScanResult parse(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            requireObject(root);
            rejectUnknown(root, ROOT_FIELDS);
            requireText(root, "schemaVersion");
            if (!SCHEMA.equals(root.get("schemaVersion").asText())) throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
            String status = text(root, "status");
            if (!STATUSES.contains(status)) throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
            String scannerId = bounded(text(root, "scannerId"), "scannerId", 128);
            String scannerVersion = bounded(text(root, "scannerVersion"), "scannerVersion", 64);
            Set<PackageSecurityScanCapability> responseCapabilities = parseCapabilities(root.get("capabilities"));
            if (!responseCapabilities.containsAll(capabilities())) {
                throw failure("EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE");
            }
            List<PackageSecurityFinding> findings = parseFindings(root.get("findings"));
            return new PackageSecurityScanResult(status, scannerId, scannerVersion, findings);
        } catch (ScannerFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
        }
    }

    private Set<PackageSecurityScanCapability> parseCapabilities(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > PackageSecurityScanCapability.values().length) {
            throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
        }
        EnumSet<PackageSecurityScanCapability> result = EnumSet.noneOf(PackageSecurityScanCapability.class);
        for (JsonNode value : node) {
            if (!value.isTextual()) throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
            try {
                result.add(PackageSecurityScanCapability.valueOf(value.asText().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
            }
        }
        return Set.copyOf(result);
    }

    private List<PackageSecurityFinding> parseFindings(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 1000) {
            throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
        }
        List<PackageSecurityFinding> findings = new ArrayList<>();
        for (JsonNode value : node) {
            requireObject(value);
            rejectUnknown(value, FINDING_FIELDS);
            String code = bounded(text(value, "code"), "finding.code", 96);
            String path = bounded(text(value, "path"), "finding.path", 256);
            String severity = text(value, "severity").toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(severity)) throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
            findings.add(new PackageSecurityFinding(code, path, severity, "外部安全扫描发现风险"));
        }
        return List.copyOf(findings);
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("digest unavailable");
        }
    }

    private byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maxBytes) throw failure("EXTERNAL_SECURITY_SCANNER_RESPONSE_TOO_LARGE");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isTextual() || node.get(field).asText().isBlank()) {
            throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
        }
        return node.get(field).asText().trim();
    }

    private String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.matches(".*[\\p{Cntrl}].*")) {
            throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
        }
        return value;
    }

    private void requireText(JsonNode node, String field) {
        text(node, field);
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
    }

    private void rejectUnknown(JsonNode node, Set<String> allowed) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) throw failure("EXTERNAL_SECURITY_SCANNER_INVALID_RESPONSE");
        }
    }

    private void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }

    private ScannerFailure failure(String code) {
        return new ScannerFailure(code);
    }

    private static final class ScannerFailure extends IllegalStateException {
        private ScannerFailure(String code) {
            super(code == null || code.isBlank() ? "EXTERNAL_SECURITY_SCANNER_UNAVAILABLE" : code);
        }
    }
}
