package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.distribution.ArtifactStorage;
import com.huawei.skillcenter.distribution.ArtifactStorageConnectivityProbe;
import com.huawei.skillcenter.distribution.ArtifactStorageHealth;
import com.huawei.skillcenter.distribution.ArtifactStorageIdentity;
import com.huawei.skillcenter.distribution.ArtifactStorageProbeResult;
import com.huawei.skillcenter.distribution.ArtifactStorageReadiness;
import com.huawei.skillcenter.distribution.ContractOnlyArtifactStorage;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStorageConnectivityProbeServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void adminProbeStoresOnlySafeConnectivityEvidenceAndReadinessConsumesReachability() {
        GovernanceStore governance = new GovernanceStore(tempDir.resolve("state.json"), List.of());
        ArtifactStorageProbeResult result = new ArtifactStorageProbeResult(
                "object-storage", "REACHABLE", "ARTIFACT_STORAGE_PROBE_OK", 200, 17,
                Instant.parse("2026-08-25T00:00:00Z"));
        ArtifactStorage storage = probeStorage(result);
        ArtifactStorageConnectivityProbeService service = new ArtifactStorageConnectivityProbeService(
                storage, governance, Clock.fixed(result.checkedAt(), ZoneOffset.UTC));

        ArtifactStorageProbeResult probed = service.probe(new Actor("admin", "admin"), "request-1");
        ArtifactStorageReadinessService readiness = new ArtifactStorageReadinessService(storage, service);

        assertThat(probed).isEqualTo(result);
        assertThat(readiness.readiness().status()).isEqualTo("READY");
        assertThat(readiness.readiness().reasonCode()).isEqualTo("ARTIFACT_STORAGE_PROBE_OK");
        assertThat(probed.toString()).doesNotContain("endpoint").doesNotContain("secret");
        assertThat(governance.snapshot().audits()).singleElement().satisfies(audit -> {
            assertThat(audit.action()).isEqualTo("ARTIFACT_STORAGE_CONNECTIVITY_PROBED");
            assertThat(audit.requestId()).isEqualTo("request-1");
            assertThat(audit.metadata()).containsEntry("status", "REACHABLE")
                    .containsEntry("httpStatus", "200")
                    .doesNotContainKey("endpoint");
        });
    }

    @Test
    void nonAdminCannotTriggerArtifactStorageProbe() {
        ArtifactStorage storage = probeStorage(new ArtifactStorageProbeResult(
                "object-storage", "REACHABLE", "ARTIFACT_STORAGE_PROBE_OK", 200, 1, Instant.EPOCH));
        ArtifactStorageConnectivityProbeService service = new ArtifactStorageConnectivityProbeService(
                storage, new GovernanceStore(tempDir.resolve("state.json"), List.of()), Clock.systemUTC());

        assertThatThrownBy(() -> service.probe(new Actor("developer", "developer"), "request-2"))
                .isInstanceOf(com.huawei.skillcenter.governance.ForbiddenException.class);
    }

    @Test
    void expiredProbeCannotKeepArtifactStorageReady() {
        Instant checkedAt = Instant.parse("2026-08-25T00:00:00Z");
        ArtifactStorage storage = probeStorage(new ArtifactStorageProbeResult(
                "object-storage", "REACHABLE", "ARTIFACT_STORAGE_PROBE_OK", 200, 1, checkedAt));
        Clock clock = Clock.fixed(Instant.parse("2026-08-25T00:06:00Z"), ZoneOffset.UTC);
        ArtifactStorageConnectivityProbeService service = new ArtifactStorageConnectivityProbeService(
                storage, new GovernanceStore(tempDir.resolve("expired-state.json"), List.of()), clock,
                Duration.ofMinutes(5));

        service.probe(new Actor("admin", "admin"), "request-expired");

        ArtifactStorageReadiness readiness = new ArtifactStorageReadinessService(storage, service).readiness();
        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("ARTIFACT_STORAGE_PROBE_EXPIRED");
    }

    @Test
    void recreatedServiceRecoversTheLatestSafeProbeEvidence() {
        Instant checkedAt = Instant.parse("2026-08-25T00:00:00Z");
        Path statePath = tempDir.resolve("recovered-state.json");
        ArtifactStorage storage = probeStorage(new ArtifactStorageProbeResult(
                "object-storage", "REACHABLE", "ARTIFACT_STORAGE_PROBE_OK", 200, 1, checkedAt));
        GovernanceStore firstGovernance = new GovernanceStore(statePath, List.of());
        ArtifactStorageConnectivityProbeService first = new ArtifactStorageConnectivityProbeService(
                storage, firstGovernance, Clock.fixed(checkedAt, ZoneOffset.UTC), Duration.ofMinutes(5));
        first.probe(new Actor("admin", "admin"), "request-recovery");

        GovernanceStore restartedGovernance = new GovernanceStore(statePath, List.of());
        ArtifactStorageConnectivityProbeService recreated = new ArtifactStorageConnectivityProbeService(
                storage, restartedGovernance,
                Clock.fixed(checkedAt.plusSeconds(30), ZoneOffset.UTC), Duration.ofMinutes(5));

        ArtifactStorageReadiness readiness = new ArtifactStorageReadinessService(storage, recreated).readiness();
        assertThat(readiness.status()).isEqualTo("READY");
        assertThat(readiness.reasonCode()).isEqualTo("ARTIFACT_STORAGE_PROBE_OK");
    }

    @Test
    void probeRecoveryCannotCrossArtifactStorageBackends() {
        Instant checkedAt = Instant.parse("2026-08-25T00:00:00Z");
        Path statePath = tempDir.resolve("backend-bound-state.json");
        Clock clock = Clock.fixed(checkedAt.plusSeconds(30), ZoneOffset.UTC);
        GovernanceStore governance = new GovernanceStore(statePath, List.of());
        ArtifactStorage remoteStorage = probeStorage(new ArtifactStorageProbeResult(
                "object-storage", "REACHABLE", "ARTIFACT_STORAGE_PROBE_OK", 200, 1, checkedAt));
        new ArtifactStorageConnectivityProbeService(remoteStorage, governance, clock, Duration.ofMinutes(5))
                .probe(new Actor("admin", "admin"), "request-remote");

        ContractOnlyArtifactStorage contractStorage = new ContractOnlyArtifactStorage();
        ArtifactStorageConnectivityProbeService recreated = new ArtifactStorageConnectivityProbeService(
                contractStorage, new GovernanceStore(statePath, List.of()), clock, Duration.ofMinutes(5));

        ArtifactStorageReadiness readiness = new ArtifactStorageReadinessService(contractStorage, recreated).readiness();
        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo(ContractOnlyArtifactStorage.UNAVAILABLE_CODE);
    }

    private ArtifactStorage probeStorage(ArtifactStorageProbeResult result) {
        class ProbeStorage implements ArtifactStorage, ArtifactStorageConnectivityProbe,
                ArtifactStorageHealth, ArtifactStorageIdentity {
            @Override
            public StoredArtifact store(Path source, String packageId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ArtifactMetadata inspect(String reference, String expectedSha256) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ArtifactResource open(String reference, String expectedSha256) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ArtifactStorageProbeResult probe() {
                return result;
            }

            @Override
            public ArtifactStorageReadiness readiness() {
                return new ArtifactStorageReadiness(result.backend(), "DEGRADED",
                        "ARTIFACT_STORAGE_TEST", "test storage");
            }

            @Override
            public String identity() {
                return "test-object-storage";
            }
        }
        return new ProbeStorage();
    }
}
