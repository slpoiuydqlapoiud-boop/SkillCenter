package com.huawei.skillcenter.release;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.quality.ProviderProbeTransportResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseTargetConnectivityProbeServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void mockTargetIsSkippedWithoutCallingTransport() {
        AtomicInteger calls = new AtomicInteger();
        ReleaseTargetConnectivityProbeService service = service("mock", "", "", reference -> "unused",
                (endpoint, credential, timeout) -> {
                    calls.incrementAndGet();
                    return ProviderProbeTransportResult.http(200, 1);
                }, new GovernanceStore(tempDir.resolve("mock.json"), List.of()), Clock.fixed(NOW, ZoneOffset.UTC));

        ReleaseTargetProbeResult result = service.probe(new Actor("admin", "admin"), "request-mock");

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.reasonCode()).isEqualTo("MOCK_RELEASE_TARGET_ACTIVE");
        assertThat(calls).hasValue(0);
    }

    @Test
    void configuredTargetSendsBearerAndStoresOnlySafeAuditEvidence() {
        AtomicReference<String> capturedEndpoint = new AtomicReference<>();
        AtomicReference<String> capturedCredential = new AtomicReference<>();
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("reachable.json"), List.of());
        ReleaseTargetConnectivityProbeService service = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value", (endpoint, credential, timeout) -> {
                    capturedEndpoint.set(endpoint);
                    capturedCredential.set(credential);
                    return ProviderProbeTransportResult.http(204, 17);
                }, governance, Clock.fixed(NOW, ZoneOffset.UTC));

        ReleaseTargetProbeResult result = service.probe(new Actor("admin", "admin"), "request-reachable");

        assertThat(result.status()).isEqualTo("REACHABLE");
        assertThat(result.reasonCode()).isEqualTo("PROBE_OK");
        assertThat(result.httpStatus()).isEqualTo(204);
        assertThat(result.latencyMs()).isEqualTo(17);
        assertThat(capturedEndpoint).hasValue("https://deploy.internal/health");
        assertThat(capturedCredential).hasValue("secret-value");
        assertThat(result.toString()).doesNotContain("deploy.internal").doesNotContain("secret-value");
        assertThat(governance.snapshot().audits()).singleElement().satisfies(audit -> {
            assertThat(audit.action()).isEqualTo("RELEASE_TARGET_CONNECTIVITY_PROBED");
            assertThat(audit.resourceId()).isEqualTo("release-target");
            assertThat(audit.requestId()).isEqualTo("request-reachable");
            assertThat(audit.metadata()).containsEntry("status", "REACHABLE")
                    .containsEntry("reason", "PROBE_OK")
                    .containsEntry("httpStatus", "204")
                    .doesNotContainKey("endpoint")
                    .doesNotContainKey("credentialRef")
                    .doesNotContainValue("secret-value");
        });
    }

    @Test
    void missingCredentialFailsClosedWithoutCallingTransport() {
        AtomicInteger calls = new AtomicInteger();
        ReleaseTargetConnectivityProbeService service = service("http", "https://deploy.internal/health",
                "secret://env/MISSING", reference -> {
                    throw new IllegalStateException("secret unavailable");
                }, (endpoint, credential, timeout) -> {
                    calls.incrementAndGet();
                    return ProviderProbeTransportResult.http(200, 1);
                }, new GovernanceStore(tempDir.resolve("missing.json"), List.of()), Clock.fixed(NOW, ZoneOffset.UTC));

        ReleaseTargetProbeResult result = service.probe(new Actor("admin", "admin"), "request-missing");

        assertThat(result.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.reasonCode()).isEqualTo("RELEASE_TARGET_NOT_CONFIGURED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void transportFailureIsStableAndExpiredEvidenceBecomesStale() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("expired.json"), List.of());
        ReleaseTargetConnectivityProbeService service = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.failure("PROBE_TIMEOUT", 1500),
                governance, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

        ReleaseTargetProbeResult result = service.probe(new Actor("admin", "admin"), "request-timeout");

        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.reasonCode()).isEqualTo("PROBE_TIMEOUT");
        assertThat(service.lastProbe().status()).isEqualTo("TIMEOUT");
    }

    @Test
    void nonAdminCannotTriggerProbe() {
        ReleaseTargetConnectivityProbeService service = service("mock", "", "", reference -> "unused",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(200, 1),
                new GovernanceStore(tempDir.resolve("forbidden.json"), List.of()), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.probe(new Actor("developer", "developer"), "request-forbidden"))
                .isInstanceOf(com.huawei.skillcenter.governance.ForbiddenException.class);
    }

    @Test
    void recreatedServiceRecoversLatestProbeAndExpiresItByTtl() {
        Path state = tempDir.resolve("recovery.json");
        GovernanceStore firstGovernance = new GovernanceStore(state, List.of());
        ReleaseTargetConnectivityProbeService first = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(204, 2),
                firstGovernance, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
        first.probe(new Actor("admin", "admin"), "request-recovery");

        ReleaseTargetConnectivityProbeService recreated = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(204, 2),
                new GovernanceStore(state, List.of()), Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        assertThat(recreated.lastProbe().status()).isEqualTo("REACHABLE");

        ReleaseTargetConnectivityProbeService expired = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(204, 2),
                new GovernanceStore(state, List.of()), Clock.fixed(NOW.plusSeconds(360), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        assertThat(expired.lastProbe().status()).isEqualTo("STALE");
        assertThat(expired.lastProbe().reasonCode()).isEqualTo("RELEASE_TARGET_PROBE_EXPIRED");
    }

    @Test
    void historyReturnsNewestMatchingSafeEvidenceWithinBoundedLimit() {
        Path state = tempDir.resolve("history.json");
        GovernanceStore governance = new GovernanceStore(state, List.of());
        ReleaseTargetConnectivityProbeService first = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(204, 2),
                governance, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
        first.probe(new Actor("admin", "admin"), "request-history-1");

        ReleaseTargetConnectivityProbeService second = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.failure("PROBE_TIMEOUT", 1500),
                governance, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), Duration.ofMinutes(5));
        second.probe(new Actor("admin", "admin"), "request-history-2");

        List<ReleaseTargetProbeResult> history = second.history(new Actor("admin", "admin"), 1);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).status()).isEqualTo("TIMEOUT");
        assertThat(history.get(0).reasonCode()).isEqualTo("PROBE_TIMEOUT");
        assertThat(history.get(0).toString()).doesNotContain("deploy.internal").doesNotContain("secret-value");
    }

    @Test
    void historyIgnoresOtherTargetFingerprintsAndRejectsNonAdmin() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("history-fingerprint.json"), List.of());
        ReleaseTargetConnectivityProbeService original = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(204, 2),
                governance, Clock.fixed(NOW, ZoneOffset.UTC));
        original.probe(new Actor("admin", "admin"), "request-original");

        ReleaseTargetConnectivityProbeService otherTarget = service("http", "https://other.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(204, 2),
                governance, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        otherTarget.probe(new Actor("admin", "admin"), "request-other");

        assertThat(original.history(new Actor("admin", "admin"), 100)).hasSize(1);
        assertThatThrownBy(() -> original.history(new Actor("developer", "developer"), 10))
                .isInstanceOf(com.huawei.skillcenter.governance.ForbiddenException.class);
    }

    @Test
    void historyIgnoresAuditEvidenceWithInvalidHttpStatus() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("history-invalid-http.json"), List.of());
        ReleaseTargetConnectivityProbeService service = service("http", "https://deploy.internal/health",
                "secret://env/DEPLOY_TOKEN", reference -> "secret-value",
                (endpoint, credential, timeout) -> ProviderProbeTransportResult.http(204, 2),
                governance, Clock.fixed(NOW, ZoneOffset.UTC));
        service.probe(new Actor("admin", "admin"), "request-invalid-http");
        governance.snapshot().audits().get(0).metadata().put("httpStatus", "999");

        assertThat(service.history(new Actor("admin", "admin"), 20)).isEmpty();
    }

    private ReleaseTargetConnectivityProbeService service(String mode, String endpoint, String credentialRef,
                                                           com.huawei.skillcenter.quality.ProviderCredentialResolver credentials,
                                                           ReleaseTargetProbeTransport transport,
                                                           GovernanceStore governance, Clock clock) {
        return service(mode, endpoint, credentialRef, credentials, transport, governance, clock, Duration.ofMinutes(5));
    }

    private ReleaseTargetConnectivityProbeService service(String mode, String endpoint, String credentialRef,
                                                           com.huawei.skillcenter.quality.ProviderCredentialResolver credentials,
                                                           ReleaseTargetProbeTransport transport,
                                                           GovernanceStore governance, Clock clock, Duration ttl) {
        return new ReleaseTargetConnectivityProbeService(mode, endpoint, credentialRef, credentials, transport,
                governance, clock, ttl);
    }
}
