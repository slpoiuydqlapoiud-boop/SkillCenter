package com.huawei.skillcenter.quality;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderProbeTransportTest {
    @Test
    void sendsBoundedStatusOnlyProbeWithExplicitProbeHeader() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
            ProviderProbeTransportResult result = new HttpProviderProbeTransport().probe(endpoint, Duration.ofSeconds(1));

            assertThat(result.httpStatus()).isEqualTo(204);
            assertThat(result.failureReason()).isBlank();
            assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        } finally {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        assertThat(exchange.getRequestMethod()).isEqualTo("GET");
        assertThat(exchange.getRequestHeaders().getFirst("X-Skill-Center-Provider-Probe")).isEqualTo("v1");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
