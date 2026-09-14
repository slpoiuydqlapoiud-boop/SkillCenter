package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderConnectivityProbeServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void mockProviderIsReportedAsSkippedWithoutCallingTransport() {
        AtomicInteger calls = new AtomicInteger();
        ProviderConnectivityProbeService service = service((endpoint, timeout) -> {
            calls.incrementAndGet();
            return ProviderProbeTransportResult.http(200, 2);
        }, Map.of("mock-runner", target("mock-runner", "runner", "mock", ProviderAdapterConfig.disabled())));

        ProviderProbeResult result = service.probe("mock-runner", new Actor("admin", "admin"), "request-1").getFirst();

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.reason()).isEqualTo("MOCK_PROVIDER_ACTIVE");
        assertThat(result.httpStatus()).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    void configuredProviderReturnsReachableSignalAndWritesSafeAudit() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        ProviderConnectivityProbeService service = service((endpoint, timeout) -> ProviderProbeTransportResult.http(204, 17),
                Map.of("openclaw-runner", target("openclaw-runner", "runner", "openclaw",
                        new ProviderAdapterConfig(true, "https://openclaw.internal", "secret://openclaw"))), governance);

        ProviderProbeResult result = service.probe("openclaw-runner", new Actor("quality-admin", "admin"), "request-2").getFirst();

        assertThat(result.status()).isEqualTo("REACHABLE");
        assertThat(result.reason()).isEqualTo("PROBE_OK");
        assertThat(result.httpStatus()).isEqualTo(204);
        assertThat(result.latencyMs()).isEqualTo(17);
        assertThat(result.toString()).doesNotContain("openclaw.internal").doesNotContain("secret://");
        assertThat(governance.snapshot().audits()).singleElement().satisfies(audit -> {
            assertThat(audit.action()).isEqualTo("PROVIDER_CONNECTIVITY_PROBED");
            assertThat(audit.requestId()).isEqualTo("request-2");
            assertThat(audit.metadata()).containsEntry("status", "REACHABLE")
                    .containsEntry("reason", "PROBE_OK")
                    .containsEntry("httpStatus", "204")
                    .doesNotContainKey("endpoint")
                    .doesNotContainKey("credentialRef");
        });
    }

    @Test
    void scheduledProbeRefreshesAllTargetsWithoutCreatingAuditEvents() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("scheduled-state.json"), List.of());
        ProviderConnectivityProbeService service = service((endpoint, timeout) ->
                        ProviderProbeTransportResult.http(204, 7),
                Map.of("openclaw-runner", target("openclaw-runner", "runner", "openclaw",
                        new ProviderAdapterConfig(true, "https://openclaw.internal", "secret://openclaw"))),
                governance);

        List<ProviderProbeResult> results = service.probeScheduled();

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.providerId()).isEqualTo("openclaw-runner");
            assertThat(result.status()).isEqualTo("REACHABLE");
        });
        assertThat(governance.snapshot().audits()).isEmpty();
    }

    @Test
    void latestScheduledProbeIsQueryableAndExpiresSafely() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-24T00:00:00Z"));
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("latest-state.json"), List.of());
        ProviderConnectivityProbeService service = new ProviderConnectivityProbeService(
                Map.of("openclaw-runner", target("openclaw-runner", "runner", "openclaw",
                        new ProviderAdapterConfig(true, "https://openclaw.internal", "secret://openclaw"))),
                (endpoint, timeout) -> ProviderProbeTransportResult.http(204, 7), governance,
                mutableClock(now), java.time.Duration.ofSeconds(30));

        service.probeScheduled();
        assertThat(service.lastProbes()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("REACHABLE");
            assertThat(result.reason()).isEqualTo("PROBE_OK");
        });

        now.set(now.get().plusSeconds(31));

        assertThat(service.lastProbes()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("STALE");
            assertThat(result.reason()).isEqualTo("PROBE_EXPIRED");
        });
    }

    @Test
    void providerFailuresAreReturnedAsStableDiagnosticStatesWithoutLeakingResponseData() {
        ProviderConnectivityProbeService service = service((endpoint, timeout) -> ProviderProbeTransportResult.failure("PROBE_TIMEOUT", 1500),
                Map.of("deepeval-evaluation", target("deepeval-evaluation", "evaluation", "deepeval",
                        new ProviderAdapterConfig(true, "https://deepeval.internal", "secret://deepeval"))));

        ProviderProbeResult result = service.probe("deepeval-evaluation", new Actor("admin", "admin"), "request-3").getFirst();

        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.reason()).isEqualTo("PROBE_TIMEOUT");
        assertThat(result.httpStatus()).isNull();
        assertThat(result.latencyMs()).isEqualTo(1500);
    }

    @Test
    void allTargetsAreReturnedInStableOrderAndUnknownTargetIsRejected() {
        ProviderConnectivityProbeService service = service((endpoint, timeout) -> ProviderProbeTransportResult.http(503, 5), Map.of(
                "langfuse-observability", target("langfuse-observability", "observability", "langfuse", new ProviderAdapterConfig(true, "https://langfuse.internal", "secret://langfuse")),
                "openclaw-runner", target("openclaw-runner", "runner", "mock", ProviderAdapterConfig.disabled())
        ));

        assertThat(service.probe(null, new Actor("admin", "admin"), "request-4")).extracting(ProviderProbeResult::providerId)
                .containsExactly("langfuse-observability", "openclaw-runner");
        assertThatThrownBy(() -> service.probe("missing", new Actor("admin", "admin"), "request-5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown provider probe target: missing");
    }

    private ProviderConnectivityProbeService service(ProviderProbeTransport transport,
                                                       Map<String, ProviderProbeTarget> targets) {
        return service(transport, targets, new GovernanceStore(tempDir.resolve("state-" + System.nanoTime() + ".json"), List.of()));
    }

    private ProviderConnectivityProbeService service(ProviderProbeTransport transport,
                                                       Map<String, ProviderProbeTarget> targets,
                                                       GovernanceStore governance) {
        return new ProviderConnectivityProbeService(targets, transport, governance,
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
    }

    private Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private ProviderProbeTarget target(String id, String kind, String mode, ProviderAdapterConfig config) {
        return new ProviderProbeTarget(id, kind, mode, config);
    }
}
