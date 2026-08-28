package com.huawei.skillcenter.release;

import com.huawei.skillcenter.quality.ProviderProbeTransportResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpReleaseTargetProbeTransportTest {
    @Test
    void sendsBoundedAuthenticatedStatusOnlyProbe() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, authorization));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
            ProviderProbeTransportResult result = new HttpReleaseTargetProbeTransport()
                    .probe(endpoint, "secret-value", Duration.ofSeconds(1));

            assertThat(result.httpStatus()).isEqualTo(204);
            assertThat(result.failureReason()).isBlank();
            assertThat(authorization).hasValue("Bearer secret-value");
        } finally {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange, AtomicReference<String> authorization) throws IOException {
        assertThat(exchange.getRequestMethod()).isEqualTo("GET");
        assertThat(exchange.getRequestHeaders().getFirst("X-Skill-Center-Release-Target-Probe")).isEqualTo("v1");
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
