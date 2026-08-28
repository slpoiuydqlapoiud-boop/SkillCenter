package com.huawei.skillcenter.packageupload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.EnvironmentProviderCredentialResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpExternalPackageSecurityScannerTest {
    private HttpServer server;
    private Path packageFile;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        packageFile = Files.createTempFile("skill-package-", ".zip");
        Files.writeString(packageFile, "zip-bytes");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop(0);
        Files.deleteIfExists(packageFile);
    }

    @Test
    void sendsBoundedPackageRequestAndMapsPassedResponse() throws Exception {
        server.createContext("/scan", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer scanner-secret");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/zip");
            assertThat(exchange.getRequestHeaders().getFirst("X-Skill-Package-Sha256")).hasSize(64);
            assertThat(new String(exchange.getRequestBody().readAllBytes())).isEqualTo("zip-bytes");
            respond(exchange, 200, response("PASSED", List.of()));
        });
        server.start();

        HttpExternalPackageSecurityScanner scanner = scanner("http://127.0.0.1:" + server.getAddress().getPort() + "/scan");

        PackageSecurityScanResult result = scanner.scan(packageFile);

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.scannerId()).isEqualTo("vendor-scanner");
        assertThat(result.scannerVersion()).isEqualTo("2026.1");
    }

    @Test
    void mapsBlockedFindingsToSafeSummaries() throws Exception {
        server.createContext("/scan", exchange -> respond(exchange, 200,
                response("BLOCKED", List.of("MALWARE_FOUND"))));
        server.start();

        PackageSecurityScanResult result = scanner("http://127.0.0.1:" + server.getAddress().getPort() + "/scan")
                .scan(packageFile);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("MALWARE_FOUND");
            assertThat(finding.reason()).isEqualTo("外部安全扫描发现风险");
        });
    }

    @Test
    void rejectsUnknownResponseFieldsAndNonSuccessStatusWithoutBodyLeak() throws Exception {
        server.createContext("/scan", exchange -> respond(exchange, 200,
                "{\"schemaVersion\":\"package-security-scan.v1\",\"status\":\"PASSED\","
                        + "\"scannerId\":\"vendor-scanner\",\"scannerVersion\":\"2026.1\","
                        + "\"capabilities\":[\"MALWARE\",\"SENSITIVE_INFORMATION\",\"DEPENDENCY_VULNERABILITY\",\"LICENSE\"],"
                        + "\"findings\":[],\"responseBody\":\"vendor-secret\"}"));
        server.start();

        assertThatThrownBy(() -> scanner("http://127.0.0.1:" + server.getAddress().getPort() + "/scan")
                .scan(packageFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("vendor-secret");

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scan", exchange -> respond(exchange, 502, "upstream-body-secret"));
        server.start();

        assertThatThrownBy(() -> scanner("http://127.0.0.1:" + server.getAddress().getPort() + "/scan")
                .scan(packageFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("upstream-body-secret");
    }

    @Test
    void rejectsOversizedResponsesBeforeParsing() throws Exception {
        server.createContext("/scan", exchange -> respond(exchange, 200, "x".repeat(5000)));
        server.start();
        ExternalPackageSecurityScannerProperties properties = properties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/scan");
        properties.setMaxResponseBytes(4096);

        assertThatThrownBy(() -> new HttpExternalPackageSecurityScanner(properties,
                new EnvironmentProviderCredentialResolver(java.util.Map.of("SCANNER_TOKEN", "scanner-secret")),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), new ObjectMapper())
                .scan(packageFile))
                .isInstanceOf(IllegalStateException.class);
    }

    private HttpExternalPackageSecurityScanner scanner(String endpoint) {
        return new HttpExternalPackageSecurityScanner(properties(endpoint),
                new EnvironmentProviderCredentialResolver(java.util.Map.of("SCANNER_TOKEN", "scanner-secret")),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), new ObjectMapper());
    }

    private ExternalPackageSecurityScannerProperties properties(String endpoint) {
        ExternalPackageSecurityScannerProperties properties = new ExternalPackageSecurityScannerProperties();
        properties.setMode("required");
        properties.setEndpoint(endpoint);
        properties.setCredentialRef("secret://env/SCANNER_TOKEN");
        properties.setCapabilities(Set.of("MALWARE", "SENSITIVE_INFORMATION",
                "DEPENDENCY_VULNERABILITY", "LICENSE"));
        properties.setRequestTimeoutMs(2000);
        return properties;
    }

    private String response(String status, List<String> findingCodes) {
        String findings = findingCodes.stream()
                .map(code -> "{\"code\":\"" + code + "\",\"path\":\"SKILL.md\",\"severity\":\"HIGH\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"schemaVersion\":\"package-security-scan.v1\",\"status\":\"" + status + "\","
                + "\"scannerId\":\"vendor-scanner\",\"scannerVersion\":\"2026.1\","
                + "\"capabilities\":[\"MALWARE\",\"SENSITIVE_INFORMATION\",\"DEPENDENCY_VULNERABILITY\",\"LICENSE\"],"
                + "\"findings\":[" + findings + "]}";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
