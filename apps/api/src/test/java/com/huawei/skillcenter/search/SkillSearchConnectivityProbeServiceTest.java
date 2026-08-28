package com.huawei.skillcenter.search;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class SkillSearchConnectivityProbeServiceTest {
    @Test
    void probeReturnsSafeMetadataAndAuditsOnlySafeFields() throws Exception {
        try (var server = new ProbeServer(200)) {
            HttpSkillSearchIndex index = new HttpSkillSearchIndex(server.endpoint(), "skills-v1", "secret://search",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 16_384, HttpClient.newHttpClient(),
                    new com.fasterxml.jackson.databind.ObjectMapper(), reference -> "super-secret-token", Clock.systemUTC());
            GovernanceStore governanceStore = mock(GovernanceStore.class);
            SkillSearchConnectivityProbeService service = new SkillSearchConnectivityProbeService(
                    index, governanceStore, Clock.systemUTC(), Duration.ofMinutes(5));

            SkillSearchProbeResult result = service.probe(new Actor("admin-user", "admin"), "request-1");

            assertThat(result.status()).isEqualTo("REACHABLE");
            assertThat(result.reasonCode()).isEqualTo("SEARCH_INDEX_PROBE_OK");
            verify(governanceStore).addAudit(argThat(event -> event.metadata().values().stream()
                    .noneMatch(value -> value.contains("super-secret-token") || value.contains("127.0.0.1"))));
        }
    }

    @Test
    void doesNotRestoreProbeEvidenceFromAnotherSearchConfiguration() throws Exception {
        HttpSkillSearchIndex index = new HttpSkillSearchIndex("http://127.0.0.1:9200", "skills-v1", "secret://search",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 16_384, HttpClient.newHttpClient(),
                new com.fasterxml.jackson.databind.ObjectMapper(), reference -> "token", Clock.systemUTC());
        GovernanceStore governanceStore = mock(GovernanceStore.class);
        when(governanceStore.snapshot()).thenReturn(new com.huawei.skillcenter.governance.GovernanceSnapshot(
                List.of(), List.of(), List.of(), List.of(new AuditEvent("audit-1", "SEARCH_INDEX_PROBED",
                        "SEARCH_INDEX", "opensearch", "admin", "admin", "request-1",
                        Instant.parse("2026-08-28T00:00:00Z"), Map.of(
                        "status", "REACHABLE", "reason", "SEARCH_INDEX_PROBE_OK", "latencyMs", "4",
                        "identity", "different-search-configuration")))));

        SkillSearchConnectivityProbeService service = new SkillSearchConnectivityProbeService(
                index, governanceStore, Clock.systemUTC(), Duration.ofMinutes(5));

        assertThat(service.lastProbe()).isNull();
    }

    private static final class ProbeServer implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;

        private ProbeServer(int status) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
