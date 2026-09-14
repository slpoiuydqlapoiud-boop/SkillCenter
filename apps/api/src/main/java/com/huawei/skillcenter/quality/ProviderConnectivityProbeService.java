package com.huawei.skillcenter.quality;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProviderConnectivityProbeService {
    static final Duration PROBE_TIMEOUT = Duration.ofMillis(2000);

    private final Map<String, ProviderProbeTarget> targets;
    private final ProviderProbeTransport transport;
    private final GovernanceStore governanceStore;
    private final Clock clock;
    private final Duration probeTtl;
    private volatile Map<String, ProviderProbeResult> lastProbes = Map.of();

    @Autowired
    public ProviderConnectivityProbeService(
            @Value("${skill-center.providers.runner:mock}") String runnerMode,
            @Value("${skill-center.providers.evaluation:mock}") String evaluationMode,
            @Value("${skill-center.providers.observability:mock}") String observabilityMode,
            @Value("${skill-center.providers.openclaw.endpoint:}") String openclawEndpoint,
            @Value("${skill-center.providers.openclaw.credential-ref:}") String openclawCredentialRef,
            @Value("${skill-center.providers.deepeval.endpoint:}") String deepevalEndpoint,
            @Value("${skill-center.providers.deepeval.credential-ref:}") String deepevalCredentialRef,
            @Value("${skill-center.providers.langfuse.endpoint:}") String langfuseEndpoint,
            @Value("${skill-center.providers.langfuse.credential-ref:}") String langfuseCredentialRef,
            @Value("${skill-center.providers.probe-ttl-seconds:300}") long probeTtlSeconds,
            HttpProviderProbeTransport transport,
            GovernanceStore governanceStore) {
        this(defaultTargets(runnerMode, evaluationMode, observabilityMode,
                        openclawEndpoint, openclawCredentialRef,
                        deepevalEndpoint, deepevalCredentialRef,
                        langfuseEndpoint, langfuseCredentialRef),
                transport, governanceStore, Clock.systemUTC(),
                Duration.ofSeconds(Math.max(1, probeTtlSeconds)));
    }

    ProviderConnectivityProbeService(Map<String, ProviderProbeTarget> targets,
                                     ProviderProbeTransport transport,
                                     GovernanceStore governanceStore,
                                     Clock clock) {
        this(targets, transport, governanceStore, clock, Duration.ofMinutes(5));
    }

    ProviderConnectivityProbeService(Map<String, ProviderProbeTarget> targets,
                                     ProviderProbeTransport transport,
                                     GovernanceStore governanceStore,
                                     Clock clock,
                                     Duration probeTtl) {
        this.targets = Map.copyOf(targets);
        this.transport = transport;
        this.governanceStore = governanceStore;
        this.clock = clock;
        this.probeTtl = probeTtl == null || probeTtl.isZero() || probeTtl.isNegative()
                ? Duration.ofSeconds(1) : probeTtl;
    }

    public List<ProviderProbeResult> probe(String providerId, Actor actor, String requestId) {
        RoleGuard.require(actor, java.util.Set.of("admin"));
        List<ProviderProbeResult> results = probeSelected(providerId);
        remember(results);
        results.forEach(result -> audit(result, actor, requestId));
        return results;
    }

    /** Refreshes all configured provider signals without creating user-facing audit events. */
    public List<ProviderProbeResult> probeScheduled() {
        List<ProviderProbeResult> results = probeSelected(null);
        remember(results);
        return results;
    }

    /** Returns the most recent safe probe result for each target, marking expired evidence stale. */
    public List<ProviderProbeResult> lastProbes() {
        return lastProbes.values().stream()
                .sorted(Comparator.comparing(ProviderProbeResult::providerId))
                .map(this::freshen)
                .toList();
    }

    private List<ProviderProbeResult> probeSelected(String providerId) {
        List<ProviderProbeTarget> selected = select(providerId);
        List<ProviderProbeResult> results = new ArrayList<>();
        for (ProviderProbeTarget target : selected) {
            ProviderProbeResult result = probeTarget(target);
            results.add(result);
        }
        return results;
    }

    private List<ProviderProbeTarget> select(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return targets.values().stream()
                    .sorted(Comparator.comparing(ProviderProbeTarget::providerId))
                    .toList();
        }
        ProviderProbeTarget target = targets.get(providerId.trim());
        if (target == null) {
            throw new IllegalArgumentException("Unknown provider probe target: " + providerId.trim());
        }
        return List.of(target);
    }

    private ProviderProbeResult probeTarget(ProviderProbeTarget target) {
        Instant checkedAt = Instant.now(clock);
        if ("mock".equals(target.mode())) {
            return result(target, "SKIPPED", "MOCK_PROVIDER_ACTIVE", null, 0, checkedAt);
        }
        if (!target.config().configured()) {
            return result(target, "NOT_CONFIGURED", "EXTERNAL_ADAPTER_NOT_CONFIGURED", null, 0, checkedAt);
        }
        ProviderProbeTransportResult transportResult = transport.probe(target.config().endpoint(), PROBE_TIMEOUT);
        if (transportResult.httpStatus() != null) {
            String status = transportResult.httpStatus() >= 200 && transportResult.httpStatus() < 300
                    ? "REACHABLE" : "HTTP_ERROR";
            String reason = status.equals("REACHABLE") ? "PROBE_OK" : "PROBE_HTTP_ERROR";
            return result(target, status, reason, transportResult.httpStatus(), transportResult.latencyMs(), checkedAt);
        }
        String reason = transportResult.failureReason().isBlank() ? "PROBE_FAILED" : transportResult.failureReason();
        String status = switch (reason) {
            case "PROBE_TIMEOUT" -> "TIMEOUT";
            case "PROBE_UNREACHABLE" -> "UNREACHABLE";
            default -> "FAILED";
        };
        return result(target, status, reason, null, transportResult.latencyMs(), checkedAt);
    }

    private ProviderProbeResult result(ProviderProbeTarget target, String status, String reason,
                                       Integer httpStatus, long latencyMs, Instant checkedAt) {
        return new ProviderProbeResult(target.providerId(), target.kind(), status, reason,
                httpStatus, latencyMs, checkedAt);
    }

    private void remember(List<ProviderProbeResult> results) {
        Map<String, ProviderProbeResult> updated = new HashMap<>(lastProbes);
        results.forEach(result -> updated.put(result.providerId(), result));
        lastProbes = Map.copyOf(updated);
    }

    private ProviderProbeResult freshen(ProviderProbeResult probe) {
        Instant now = clock.instant();
        if (probe.checkedAt() == null || probe.checkedAt().isAfter(now)
                || Duration.between(probe.checkedAt(), now).compareTo(probeTtl) > 0) {
            return new ProviderProbeResult(probe.providerId(), probe.kind(), "STALE", "PROBE_EXPIRED",
                    probe.httpStatus(), probe.latencyMs(), probe.checkedAt());
        }
        return probe;
    }

    private void audit(ProviderProbeResult result, Actor actor, String requestId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", result.status());
        metadata.put("reason", result.reason());
        metadata.put("latencyMs", String.valueOf(result.latencyMs()));
        if (result.httpStatus() != null) {
            metadata.put("httpStatus", String.valueOf(result.httpStatus()));
        }
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(),
                "PROVIDER_CONNECTIVITY_PROBED", "PROVIDER", result.providerId(),
                actor.userId(), actor.role(), requestId, result.checkedAt(), metadata));
    }

    private static Map<String, ProviderProbeTarget> defaultTargets(String runnerMode,
                                                                     String evaluationMode,
                                                                     String observabilityMode,
                                                                     String openclawEndpoint,
                                                                     String openclawCredentialRef,
                                                                     String deepevalEndpoint,
                                                                     String deepevalCredentialRef,
                                                                     String langfuseEndpoint,
                                                                     String langfuseCredentialRef) {
        return Map.of(
                "openclaw-runner", target("openclaw-runner", "runner", runnerMode, openclawEndpoint, openclawCredentialRef),
                "deepeval-evaluation", target("deepeval-evaluation", "evaluation", evaluationMode, deepevalEndpoint, deepevalCredentialRef),
                "langfuse-observability", target("langfuse-observability", "observability", observabilityMode, langfuseEndpoint, langfuseCredentialRef)
        );
    }

    private static ProviderProbeTarget target(String providerId, String kind, String mode,
                                              String endpoint, String credentialRef) {
        String normalizedMode = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        return new ProviderProbeTarget(providerId, kind, normalizedMode,
                new ProviderAdapterConfig(!"mock".equals(normalizedMode), endpoint, credentialRef));
    }
}
