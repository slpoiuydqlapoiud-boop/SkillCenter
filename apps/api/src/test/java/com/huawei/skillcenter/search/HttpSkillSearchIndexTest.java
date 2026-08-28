package com.huawei.skillcenter.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class HttpSkillSearchIndexTest {

    @Test
    void parsesOnlyAllowlistedSearchHits() throws Exception {
        try (var server = new SearchHttpServer(200,
                "{\"hits\":{\"hits\":[{\"_id\":\"skill-a\",\"_score\":12.5,"
                        + "\"matchedFields\":[\"id\",\"name\"],\"_source\":{\"prompt\":\"must-not-be-read\"},"
                        + "\"unknown\":\"ignored\"}]}}")) {
            HttpSkillSearchIndex index = new HttpSkillSearchIndex(server.endpoint(), "skills-v1", "secret://search",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 16_384, HttpClient.newHttpClient(),
                    new ObjectMapper(), reference -> "token-value", Clock.systemUTC());

            index.probe();
            List<SkillSearchHit> hits = index.search(new SkillSearchQuery("skill-a", "", "", "", "updated"));

            assertThat(hits).containsExactly(new SkillSearchHit("skill-a", 12.5, List.of("id", "name")));
            assertThat(server.requestBody()).contains("skill-a").doesNotContain("prompt");
        }
    }

    @Test
    void rejectsOversizedResponseWithStableReasonCode() throws Exception {
        try (var server = new SearchHttpServer(200, "x".repeat(128))) {
            HttpSkillSearchIndex index = new HttpSkillSearchIndex(server.endpoint(), "skills-v1", "secret://search",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 64, HttpClient.newHttpClient(),
                    new ObjectMapper(), reference -> "token-value", Clock.systemUTC());

            index.probe();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(SkillSearchIndexRemoteException.class,
                    () -> index.search(new SkillSearchQuery("skill-a", "", "", "", "updated")));

            assertThat(failure.reasonCode()).isEqualTo("SEARCH_INDEX_RESPONSE_TOO_LARGE");
        }
    }

    @Test
    void keepsPreviousStatusWhenBulkRebuildFails() throws Exception {
        try (var server = new SearchHttpServer(503, "{}")) {
            HttpSkillSearchIndex index = new HttpSkillSearchIndex(server.endpoint(), "skills-v1", "secret://search",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 16_384, HttpClient.newHttpClient(),
                    new ObjectMapper(), reference -> "token-value", Clock.systemUTC());
            SkillSearchDocument document = new SkillSearchDocument("skill-a", "A", "Description", List.of("tag"),
                    "team-a", "category-a", "published", "low", null, null, "1.0.0", "PUBLIC", "");

            var failure = org.junit.jupiter.api.Assertions.assertThrows(SkillSearchIndexRemoteException.class,
                    () -> index.rebuild(List.of(document), "hash-a"));

            assertThat(failure.reasonCode()).isEqualTo("SEARCH_INDEX_UPSTREAM_UNAVAILABLE");
            assertThat(index.status().state()).isEqualTo("NOT_READY");
            assertDoesNotThrow(() -> index.status());
        }
    }

    @Test
    void rebuildWithSameSourceHashIsIdempotent() throws Exception {
        try (var server = new SearchHttpServer(200, "{\"errors\":false}")) {
            HttpSkillSearchIndex index = new HttpSkillSearchIndex(server.endpoint(), "skills-v1", "secret://search",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 16_384, HttpClient.newHttpClient(),
                    new ObjectMapper(), reference -> "token-value", Clock.systemUTC());
            SkillSearchDocument document = new SkillSearchDocument("skill-a", "A", "Description", List.of("tag"),
                    "team-a", "category-a", "published", "low", null, null, "1.0.0", "PUBLIC", "");

            SkillSearchRebuildResult first = index.rebuild(List.of(document), "hash-a");
            SkillSearchRebuildResult second = index.rebuild(List.of(document), "hash-a");

            assertThat(second.revision()).isEqualTo(first.revision());
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void bulkRebuildSendsIndexableMetadataAsTheDocumentSource() throws Exception {
        try (var server = new SearchHttpServer(200, "{\"errors\":false}")) {
            HttpSkillSearchIndex index = new HttpSkillSearchIndex(server.endpoint(), "skills-v1", "secret://search",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 16_384, HttpClient.newHttpClient(),
                    new ObjectMapper(), reference -> "token-value", Clock.systemUTC());
            SkillSearchDocument document = new SkillSearchDocument("skill-a", "A", "Description", List.of("tag"),
                    "team-a", "category-a", "published", "low", null, null, "1.0.0", "PUBLIC", "");

            index.rebuild(List.of(document), "hash-a");

            String[] lines = server.requestBody().split("\\R");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).contains("\"index\"").contains("\"_id\":\"skill-a\"");
            assertThat(lines[1]).contains("\"skillId\":\"skill-a\"").doesNotContain("\"doc\"");
        }
    }

    @Test
    void refusesSearchUntilARecentReachableProbeExists() throws Exception {
        try (var server = new SearchHttpServer(200,
                "{\"hits\":{\"hits\":[{\"_id\":\"skill-a\",\"_score\":1,\"matchedFields\":[\"id\"]}]}}")) {
            HttpSkillSearchIndex index = new HttpSkillSearchIndex(server.endpoint(), "skills-v1", "secret://search",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 16_384, HttpClient.newHttpClient(),
                    new ObjectMapper(), reference -> "token-value", Clock.systemUTC());

            var failure = org.junit.jupiter.api.Assertions.assertThrows(SkillSearchIndexRemoteException.class,
                    () -> index.search(new SkillSearchQuery("skill-a", "", "", "", "updated")));

            assertThat(failure.reasonCode()).isEqualTo("SEARCH_INDEX_PROBE_EXPIRED");
            index.probe();
            assertThat(index.search(new SkillSearchQuery("skill-a", "", "", "", "updated"))).hasSize(1);
        }
    }

    private static final class SearchHttpServer implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();
        private volatile String requestBody = "";

        private SearchHttpServer(int status, String response) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requestCount.incrementAndGet();
                requestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String requestBody() {
            return requestBody;
        }

        private int requestCount() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
