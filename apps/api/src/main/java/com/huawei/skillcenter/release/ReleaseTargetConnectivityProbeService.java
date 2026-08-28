package com.huawei.skillcenter.release;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.quality.ProviderCredentialResolver;
import com.huawei.skillcenter.quality.ProviderProbeTransportResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executes explicit, authenticated, status-only release target connectivity probes. */
@Service
public class ReleaseTargetConnectivityProbeService {
    static final Duration PROBE_TIMEOUT = Duration.ofMillis(2000);
    private static final String TARGET_ID = "release-target";
    private static final String AUDIT_ACTION = "RELEASE_TARGET_CONNECTIVITY_PROBED";
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final Set<String> SAFE_STATUSES = Set.of(
            "REACHABLE", "NOT_CONFIGURED", "UNREACHABLE", "HTTP_ERROR", "FAILED", "TIMEOUT", "SKIPPED", "STALE");

    private final String mode;
    private final String endpoint;
    private final String credentialRef;
    private final ProviderCredentialResolver credentials;
    private final ReleaseTargetProbeTransport transport;
    private final GovernanceStore governanceStore;
    private final Clock clock;
    private final Duration probeTtl;
    private volatile ReleaseTargetProbeResult lastProbe;

    @Autowired
    public ReleaseTargetConnectivityProbeService(
            @Value("${skill-center.release-target.mode:mock}") String mode,
            @Value("${skill-center.release-target.endpoint:}") String endpoint,
            @Value("${skill-center.release-target.credential-ref:}") String credentialRef,
            @Value("${skill-center.release-target.probe-ttl-seconds:300}") long probeTtlSeconds,
            ProviderCredentialResolver credentials,
            ReleaseTargetProbeTransport transport,
            GovernanceStore governanceStore) {
        this(mode, endpoint, credentialRef, credentials, transport, governanceStore, Clock.systemUTC(),
                Duration.ofSeconds(Math.max(1, probeTtlSeconds)));
    }

    ReleaseTargetConnectivityProbeService(String mode, String endpoint, String credentialRef,
                                           ProviderCredentialResolver credentials,
                                           ReleaseTargetProbeTransport transport,
                                           GovernanceStore governanceStore, Clock clock, Duration probeTtl) {
        this.mode = normalize(mode).toLowerCase(Locale.ROOT);
        this.endpoint = normalize(endpoint);
        this.credentialRef = normalize(credentialRef);
        this.credentials = credentials;
        this.transport = transport;
        this.governanceStore = governanceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.probeTtl = probeTtl == null || probeTtl.isZero() || probeTtl.isNegative()
                ? Duration.ofSeconds(1) : probeTtl;
        this.lastProbe = restoreLastProbe();
    }

    public ReleaseTargetProbeResult probe(Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        ReleaseTargetProbeResult result = probeTarget();
        lastProbe = result;
        audit(result, actor, requestId);
        return result;
    }

    public ReleaseTargetProbeResult lastProbe() {
        ReleaseTargetProbeResult probe = lastProbe;
        if (probe == null) return null;
        Instant now = clock.instant();
        if (probe.checkedAt().isAfter(now) || Duration.between(probe.checkedAt(), now).compareTo(probeTtl) > 0) {
            return new ReleaseTargetProbeResult(TARGET_ID, "STALE", "RELEASE_TARGET_PROBE_EXPIRED",
                    probe.httpStatus(), probe.latencyMs(), probe.checkedAt());
        }
        return probe;
    }

    public List<ReleaseTargetProbeResult> history(Actor actor, int requestedLimit) {
        RoleGuard.require(actor, Set.of("admin"));
        if (governanceStore == null || governanceStore.snapshot().audits() == null) return List.of();
        int limit = requestedLimit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(MAX_HISTORY_LIMIT, requestedLimit);
        return governanceStore.snapshot().audits().stream()
                .map(this::restore)
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing(ReleaseTargetProbeResult::checkedAt).reversed())
                .limit(limit)
                .toList();
    }

    private ReleaseTargetProbeResult probeTarget() {
        Instant checkedAt = clock.instant();
        if ("mock".equals(mode)) {
            return result("SKIPPED", "MOCK_RELEASE_TARGET_ACTIVE", null, 0, checkedAt);
        }
        if (!"http".equals(mode)) {
            return result("NOT_CONFIGURED", "RELEASE_TARGET_MODE_UNSUPPORTED", null, 0, checkedAt);
        }
        if (endpoint.isBlank() || credentialRef.isBlank() || credentials == null || transport == null) {
            return result("NOT_CONFIGURED", "RELEASE_TARGET_NOT_CONFIGURED", null, 0, checkedAt);
        }
        String credential;
        try {
            credential = credentials.resolve(credentialRef);
            if (credential == null || credential.isBlank()) {
                return result("NOT_CONFIGURED", "RELEASE_TARGET_NOT_CONFIGURED", null, 0, checkedAt);
            }
        } catch (RuntimeException exception) {
            return result("NOT_CONFIGURED", "RELEASE_TARGET_NOT_CONFIGURED", null, 0, checkedAt);
        }
        ProviderProbeTransportResult transportResult;
        try {
            transportResult = transport.probe(endpoint, credential, PROBE_TIMEOUT);
        } catch (RuntimeException exception) {
            return result("FAILED", "RELEASE_TARGET_PROBE_FAILED", null, 0, checkedAt);
        }
        if (transportResult == null) {
            return result("FAILED", "RELEASE_TARGET_PROBE_FAILED", null, 0, checkedAt);
        }
        if (transportResult.httpStatus() != null) {
            boolean reachable = transportResult.httpStatus() >= 200 && transportResult.httpStatus() < 300;
            return result(reachable ? "REACHABLE" : "HTTP_ERROR",
                    reachable ? "PROBE_OK" : "PROBE_HTTP_ERROR", transportResult.httpStatus(),
                    transportResult.latencyMs(), checkedAt);
        }
        String reason = transportResult.failureReason().isBlank()
                ? "RELEASE_TARGET_PROBE_FAILED" : transportResult.failureReason();
        String status = switch (reason) {
            case "PROBE_TIMEOUT" -> "TIMEOUT";
            case "PROBE_UNREACHABLE" -> "UNREACHABLE";
            default -> "FAILED";
        };
        return result(status, stableReason(reason), null, transportResult.latencyMs(), checkedAt);
    }

    private ReleaseTargetProbeResult result(String status, String reason, Integer httpStatus,
                                            long latencyMs, Instant checkedAt) {
        return new ReleaseTargetProbeResult(TARGET_ID, status, stableReason(reason), httpStatus, latencyMs, checkedAt);
    }

    private void audit(ReleaseTargetProbeResult result, Actor actor, String requestId) {
        if (governanceStore == null) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", result.status());
        metadata.put("reason", result.reasonCode());
        metadata.put("latencyMs", String.valueOf(result.latencyMs()));
        metadata.put("adapterId", targetFingerprint());
        if (result.httpStatus() != null) metadata.put("httpStatus", String.valueOf(result.httpStatus()));
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), AUDIT_ACTION, "RELEASE_TARGET",
                TARGET_ID, actor.userId(), actor.role(), requestId, result.checkedAt(), metadata));
    }

    private ReleaseTargetProbeResult restoreLastProbe() {
        if (governanceStore == null || governanceStore.snapshot().audits() == null) return null;
        ReleaseTargetProbeResult latest = null;
        for (AuditEvent event : governanceStore.snapshot().audits()) {
            ReleaseTargetProbeResult restored = restore(event);
            if (restored != null && (latest == null || restored.checkedAt().isAfter(latest.checkedAt()))) latest = restored;
        }
        return latest;
    }

    private ReleaseTargetProbeResult restore(AuditEvent event) {
        if (event == null || !AUDIT_ACTION.equals(event.action()) || !"RELEASE_TARGET".equals(event.resourceType())
                || !TARGET_ID.equals(event.resourceId()) || event.occurredAt() == null || event.metadata() == null
                || !targetFingerprint().equals(event.metadata().get("adapterId"))) return null;
        String status = event.metadata().get("status");
        String reason = event.metadata().get("reason");
        String latencyValue = event.metadata().get("latencyMs");
        if (!SAFE_STATUSES.contains(status) || !stableCode(reason) || latencyValue == null) return null;
        long latency;
        try {
            latency = Long.parseLong(latencyValue);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (latency < 0 || latency > 120_000) return null;
        Integer httpStatus = null;
        String httpValue = event.metadata().get("httpStatus");
        if (httpValue != null && !httpValue.isBlank()) {
            try {
                httpStatus = Integer.valueOf(httpValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (httpStatus < 100 || httpStatus > 599) return null;
        }
        return new ReleaseTargetProbeResult(TARGET_ID, status, reason, httpStatus, latency, event.occurredAt());
    }

    private String targetFingerprint() {
        String input = mode + "\u0000" + endpoint + "\u0000" + credentialRef;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "release-target-default";
        }
    }

    private static String stableReason(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        return stableCode(normalized) ? normalized : "RELEASE_TARGET_PROBE_FAILED";
    }

    private static boolean stableCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_.:-]{2,63}");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
