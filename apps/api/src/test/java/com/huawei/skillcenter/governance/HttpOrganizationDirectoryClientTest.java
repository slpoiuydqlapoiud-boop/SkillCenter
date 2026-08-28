package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpOrganizationDirectoryClientTest {
    private HttpServer server;
    private String body;
    private int status;
    private String authorization;

    @BeforeEach
    void setUp() throws IOException {
        body = validBody();
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/snapshot", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchesBoundedSnapshotWithResolvedBearerToken() {
        HttpOrganizationDirectoryClient client = client();

        OrganizationDirectorySnapshot snapshot = client.fetch();

        assertEquals("corp-directory", snapshot.source());
        assertEquals("rev-1", snapshot.revision());
        assertEquals("Bearer directory-secret", authorization);
    }

    @Test
    void rejectsUnknownFieldsAndMalformedResponsesWithoutLeakingBody() {
        body = validBody().replace("\"teams\"", "\"unexpected\":\"secret-value\",\"teams\"");

        OrganizationDirectoryUnavailableException exception = assertThrows(
                OrganizationDirectoryUnavailableException.class, () -> client().fetch());

        assertEquals("DIRECTORY_INVALID_RESPONSE", exception.reasonCode());
        assertEquals("Organization directory unavailable", exception.getMessage());
        assertTrue(!exception.getMessage().contains("secret-value"));
    }

    @Test
    void mapsNonSuccessResponseToStableError() {
        status = 503;
        body = "upstream-secret-body";

        OrganizationDirectoryUnavailableException exception = assertThrows(
                OrganizationDirectoryUnavailableException.class, () -> client().fetch());

        assertEquals("DIRECTORY_HTTP_STATUS", exception.reasonCode());
        assertEquals("Organization directory unavailable", exception.getMessage());
    }

    @Test
    void rejectsResponseBeyondConfiguredByteLimit() {
        body = "x".repeat(4_097);
        OrganizationDirectoryProperties properties = properties();
        properties.setMaxResponseBytes(4_096);

        OrganizationDirectoryUnavailableException exception = assertThrows(
                OrganizationDirectoryUnavailableException.class,
                () -> new HttpOrganizationDirectoryClient(properties, reference -> "directory-secret",
                        HttpClient.newHttpClient(), new ObjectMapper(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).fetch());

        assertEquals("DIRECTORY_RESPONSE_TOO_LARGE", exception.reasonCode());
    }

    @Test
    void rejectsMissingCredentialBeforeNetworkCall() {
        OrganizationDirectoryUnavailableException exception = assertThrows(
                OrganizationDirectoryUnavailableException.class,
                () -> new HttpOrganizationDirectoryClient(properties(), reference -> "",
                        HttpClient.newHttpClient(), new ObjectMapper(), Clock.systemUTC()).fetch());

        assertEquals("DIRECTORY_CREDENTIAL_UNAVAILABLE", exception.reasonCode());
    }

    private HttpOrganizationDirectoryClient client() {
        return new HttpOrganizationDirectoryClient(properties(), reference -> "directory-secret",
                HttpClient.newHttpClient(), new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-25T06:00:05Z"), ZoneOffset.UTC));
    }

    private OrganizationDirectoryProperties properties() {
        OrganizationDirectoryProperties properties = new OrganizationDirectoryProperties();
        properties.setMode("http");
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/snapshot");
        properties.setCredentialRef("secret://env/DIRECTORY_TOKEN");
        properties.setMaxResponseBytes(1_048_576);
        properties.validate();
        return properties;
    }

    private void respond(HttpExchange exchange) throws IOException {
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String validBody() {
        return "{\"schemaVersion\":\"organization-directory.v1\",\"source\":\"corp-directory\","
                + "\"revision\":\"rev-1\",\"fetchedAt\":\"2026-08-25T06:00:01Z\",\"teams\":["
                + "{\"teamId\":\"team-a\",\"name\":\"A\",\"status\":\"active\","
                + "\"memberUserIds\":[\"alice\"]}]}";
    }
}
