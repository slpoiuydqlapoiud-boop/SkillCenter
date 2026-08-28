package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.distribution.ArtifactStorage;
import com.huawei.skillcenter.distribution.ArtifactStorageConnectivityProbe;
import com.huawei.skillcenter.distribution.ArtifactStorageHealth;
import com.huawei.skillcenter.distribution.ArtifactStorageIdentity;
import com.huawei.skillcenter.distribution.ArtifactStorageProbeResult;
import com.huawei.skillcenter.distribution.ArtifactStorageReadiness;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executes an explicit, metadata-only artifact storage connectivity probe. */
@Service
public class ArtifactStorageConnectivityProbeService {
    private static final String PROBE_AUDIT_ACTION = "ARTIFACT_STORAGE_CONNECTIVITY_PROBED";
    private static final String ARTIFACT_STORAGE_RESOURCE = "ARTIFACT_STORAGE";
    private static final Set<String> SAFE_STATUSES = Set.of(
            "REACHABLE", "NOT_CONFIGURED", "UNREACHABLE", "HTTP_ERROR", "FAILED", "SKIPPED", "STALE");
    private final ArtifactStorage storage;
    private final GovernanceStore governanceStore;
    private final Clock clock;
    private final Duration probeTtl;
    private volatile ArtifactStorageProbeResult lastProbe;

    @Autowired
    public ArtifactStorageConnectivityProbeService(ArtifactStorage storage,
                                                   GovernanceStore governanceStore,
                                                   @Value("${skill-center.artifact-storage.probe-ttl-seconds:300}") long probeTtlSeconds) {
        this(storage, governanceStore, Clock.systemUTC(), Duration.ofSeconds(Math.max(1, probeTtlSeconds)));
    }

    public ArtifactStorageConnectivityProbeService(ArtifactStorage storage,
                                                   GovernanceStore governanceStore,
                                                   Clock clock) {
        this(storage, governanceStore, clock, Duration.ofMinutes(5));
    }

    public ArtifactStorageConnectivityProbeService(ArtifactStorage storage,
                                                   GovernanceStore governanceStore,
                                                   Clock clock,
                                                   Duration probeTtl) {
        this.storage = storage;
        this.governanceStore = governanceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.probeTtl = probeTtl == null || probeTtl.isZero() || probeTtl.isNegative()
                ? Duration.ofSeconds(1) : probeTtl;
        this.lastProbe = restoreLastProbe();
    }

    public ArtifactStorageProbeResult probe(Actor actor, String requestId) {
        RoleGuard.require(actor, java.util.Set.of("admin"));
        ArtifactStorageProbeResult result;
        if (storage instanceof ArtifactStorageConnectivityProbe probe) {
            result = probe.probe();
        } else {
            result = new ArtifactStorageProbeResult("local", "SKIPPED",
                    "ARTIFACT_STORAGE_LOCAL_ONLY", null, 0, clock.instant());
        }
        if (result == null) {
            result = new ArtifactStorageProbeResult("unknown", "FAILED",
                    "ARTIFACT_STORAGE_PROBE_FAILED", null, 0, clock.instant());
        }
        lastProbe = result;
        audit(result, actor, requestId);
        return result;
    }

    public ArtifactStorageProbeResult lastProbe() {
        ArtifactStorageProbeResult probe = lastProbe;
        if (probe == null) return null;
        Instant now = clock.instant();
        if (probe.checkedAt().isAfter(now)
                || Duration.between(probe.checkedAt(), now).compareTo(probeTtl) > 0) {
            return new ArtifactStorageProbeResult(probe.backend(), "STALE",
                    "ARTIFACT_STORAGE_PROBE_EXPIRED", probe.httpStatus(), probe.latencyMs(), probe.checkedAt());
        }
        return probe;
    }

    private void audit(ArtifactStorageProbeResult result, Actor actor, String requestId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", result.status());
        metadata.put("reason", result.reasonCode());
        metadata.put("latencyMs", String.valueOf(result.latencyMs()));
        String identity = currentIdentity();
        if (identity != null) metadata.put("adapterId", identity);
        if (result.httpStatus() != null) metadata.put("httpStatus", String.valueOf(result.httpStatus()));
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(),
                PROBE_AUDIT_ACTION, ARTIFACT_STORAGE_RESOURCE, result.backend(),
                actor.userId(), actor.role(), requestId, result.checkedAt(), metadata));
    }

    private ArtifactStorageProbeResult restoreLastProbe() {
        ArtifactStorageProbeResult latest = null;
        String backend = currentBackend();
        String identity = currentIdentity();
        if (backend == null || identity == null || governanceStore == null || governanceStore.snapshot().audits() == null) return null;
        for (AuditEvent event : governanceStore.snapshot().audits()) {
            ArtifactStorageProbeResult restored = restore(event, backend, identity);
            if (restored == null) continue;
            if (latest == null || restored.checkedAt().isAfter(latest.checkedAt())) latest = restored;
        }
        return latest;
    }

    private String currentBackend() {
        if (!(storage instanceof ArtifactStorageHealth health)) return null;
        try {
            ArtifactStorageReadiness readiness = health.readiness();
            if (readiness == null || readiness.backend() == null || readiness.backend().isBlank()) return null;
            return readiness.backend();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String currentIdentity() {
        if (!(storage instanceof ArtifactStorageIdentity identity)) return null;
        String value = identity.identity();
        return value == null || value.isBlank() ? null : value;
    }

    private ArtifactStorageProbeResult restore(AuditEvent event, String backend, String identity) {
        if (event == null || !PROBE_AUDIT_ACTION.equals(event.action())
                || !ARTIFACT_STORAGE_RESOURCE.equals(event.resourceType())
                || !backend.equals(event.resourceId())
                || event.occurredAt() == null || event.metadata() == null) return null;
        if (!identity.equals(event.metadata().get("adapterId"))) return null;
        String status = event.metadata().get("status");
        String reason = event.metadata().get("reason");
        String latencyValue = event.metadata().get("latencyMs");
        if (!SAFE_STATUSES.contains(status) || reason == null || reason.isBlank() || latencyValue == null) return null;
        long latency;
        try {
            latency = Long.parseLong(latencyValue);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (latency < 0) return null;
        Integer httpStatus = null;
        String httpStatusValue = event.metadata().get("httpStatus");
        if (httpStatusValue != null && !httpStatusValue.isBlank()) {
            try {
                httpStatus = Integer.valueOf(httpStatusValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return new ArtifactStorageProbeResult(event.resourceId(), status, reason, httpStatus, latency, event.occurredAt());
    }
}
