package com.huawei.skillcenter.quality;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaHttpProviderTransportTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsJsonWithBearerCredentialAndReturnsResponseWithoutExposingSecretsInToString() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/execute", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"status\":\"SUCCEEDED\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ProviderHttpRequest request = new ProviderHttpRequest("openclaw-runner",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/execute"),
                "super-secret-token", "{\"skillId\":\"skill-a\"}", Duration.ofSeconds(2));

        ProviderHttpResponse response = new JavaHttpProviderTransport().post(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("SUCCEEDED");
        assertThat(response.toString()).contains("body=<redacted>").doesNotContain("SUCCEEDED");
        assertThat(authorization).hasValue("Bearer super-secret-token");
        assertThat(body).hasValue("{\"skillId\":\"skill-a\"}");
        assertThat(request.toString()).contains("<redacted>")
                .doesNotContain("super-secret-token")
                .doesNotContain("skill-a");
    }

    @Test
    void mapsTimeoutToStableProviderError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();

        ProviderHttpRequest request = new ProviderHttpRequest("openclaw-runner",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"),
                "token", "{}", Duration.ofMillis(20));

        assertThatThrownBy(() -> new JavaHttpProviderTransport().post(request))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(error -> assertThat(((ProviderUnavailableException) error).code())
                        .isEqualTo("UPSTREAM_TIMEOUT"));
    }

    @Test
    void classifiesRateLimitAndUpstreamFailureWithoutReturningTheirBodiesAsErrors() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rate", exchange -> respond(exchange, 429, "secret upstream body"));
        server.createContext("/failure", exchange -> respond(exchange, 503, "private failure body"));
        server.start();

        assertThatThrownBy(() -> new JavaHttpProviderTransport().post(request("/rate")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("RATE_LIMITED: openclaw-runner");
        assertThatThrownBy(() -> new JavaHttpProviderTransport().post(request("/failure")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("TEMPORARY_UNAVAILABLE: openclaw-runner")
                .hasMessageNotContaining("private failure body");
    }

    @Test
    void rejectsOversizedResponseAtTheStreamingBoundaryWithStableError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oversized", exchange -> {
            byte[] response = "x".repeat(256_001).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> new JavaHttpProviderTransport().post(request("/oversized")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_RESPONSE_TOO_LARGE: openclaw-runner");
    }

    @Test
    void rejectsOversizedChunkedResponseWhileReading() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chunked-oversized", exchange -> {
            byte[] response = "x".repeat(256_001).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> new JavaHttpProviderTransport().post(request("/chunked-oversized")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_RESPONSE_TOO_LARGE: openclaw-runner");
    }

    @Test
    void resolvesOnlySafeEnvironmentSecretReferences() {
        EnvironmentProviderCredentialResolver resolver =
                new EnvironmentProviderCredentialResolver(Map.of("SKILLCENTER_TOKEN", "token-value"));

        assertThat(resolver.resolve("secret://env/SKILLCENTER_TOKEN")).isEqualTo("token-value");
        assertThatThrownBy(() -> resolver.resolve("token-value"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("EXTERNAL_ADAPTER_NOT_CONFIGURED: provider-credential");
        assertThatThrownBy(() -> resolver.resolve("secret://env/MISSING"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("EXTERNAL_ADAPTER_NOT_CONFIGURED: provider-credential");
    }

    private ProviderHttpRequest request(String path) {
        return new ProviderHttpRequest("openclaw-runner",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
                "token", "{}", Duration.ofSeconds(2));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] response = body.getBytes();
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
