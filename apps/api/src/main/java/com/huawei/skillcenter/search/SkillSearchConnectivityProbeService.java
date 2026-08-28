package com.huawei.skillcenter.search;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Admin-only, metadata-only probe boundary for external Skill search. */
@Service
public class SkillSearchConnectivityProbeService {
    private static final String AUDIT_ACTION = "SEARCH_INDEX_PROBED";
    private static final String RESOURCE_TYPE = "SEARCH_INDEX";
    private static final Set<String> SAFE_STATUSES = Set.of("REACHABLE", "NOT_CONFIGURED", "HTTP_ERROR",
            "UNREACHABLE", "FAILED", "TIMEOUT", "SKIPPED", "STALE");

    private final SkillSearchIndex index;
    private final GovernanceStore governanceStore;
    private final Clock clock;
    private final Duration probeTtl;
    private volatile SkillSearchProbeResult lastProbe;

    @Autowired
    public SkillSearchConnectivityProbeService(
            SkillSearchIndex index,
            GovernanceStore governanceStore,
            @Value("${skill-center.search-index.probe-ttl-seconds:300}") long probeTtlSeconds) {
        this(index, governanceStore, Clock.systemUTC(), Duration.ofSeconds(Math.max(1, probeTtlSeconds)));
    }

    SkillSearchConnectivityProbeService(SkillSearchIndex index, GovernanceStore governanceStore,
                                        Clock clock, Duration probeTtl) {
        this.index = require(index, "index");
        this.governanceStore = governanceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.probeTtl = probeTtl == null || probeTtl.isZero() || probeTtl.isNegative()
                ? Duration.ofSeconds(1) : probeTtl;
        this.lastProbe = restoreLastProbe();
    }

    public SkillSearchProbeResult probe(Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        SkillSearchProbeResult result = index instanceof SkillSearchRemoteHealth remoteHealth
                ? remoteHealth.probe()
                : new SkillSearchProbeResult(index.backend(), "SKIPPED", "SEARCH_INDEX_LOCAL_BACKEND", null, 0,
                clock.instant());
        lastProbe = result;
        audit(result, actor, requestId);
        return result;
    }

    public SkillSearchProbeResult lastProbe() {
        SkillSearchProbeResult current = lastProbe;
        if (current == null) return null;
        if (!probeFresh()) {
            return new SkillSearchProbeResult(current.backend(), "STALE", "SEARCH_INDEX_PROBE_EXPIRED",
                    current.httpStatus(), current.latencyMs(), current.checkedAt());
        }
        return current;
    }

    public boolean probeFresh() {
        SkillSearchProbeResult current = lastProbe;
        if (current == null || !"REACHABLE".equals(current.status())) return false;
        Duration age = Duration.between(current.checkedAt(), Instant.now(clock));
        return !age.isNegative() && age.compareTo(probeTtl) <= 0;
    }

    private void audit(SkillSearchProbeResult result, Actor actor, String requestId) {
        if (governanceStore == null) return;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("backend", result.backend());
        metadata.put("status", result.status());
        metadata.put("reason", result.reasonCode());
        metadata.put("latencyMs", String.valueOf(result.latencyMs()));
        if (result.httpStatus() != null) metadata.put("httpStatus", String.valueOf(result.httpStatus()));
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), AUDIT_ACTION, RESOURCE_TYPE,
                result.backend(), actor.userId(), actor.role(), requestId == null ? "" : requestId,
                result.checkedAt(), metadata));
    }

    private SkillSearchProbeResult restoreLastProbe() {
        if (governanceStore == null || governanceStore.snapshot() == null
                || governanceStore.snapshot().audits() == null) return null;
        SkillSearchProbeResult latest = null;
        for (AuditEvent event : governanceStore.snapshot().audits()) {
            SkillSearchProbeResult restored = restore(event);
            if (restored != null && (latest == null || restored.checkedAt().isAfter(latest.checkedAt()))) latest = restored;
        }
        return latest;
    }

    private SkillSearchProbeResult restore(AuditEvent event) {
        if (event == null || !AUDIT_ACTION.equals(event.action()) || !RESOURCE_TYPE.equals(event.resourceType())
                || !index.backend().equals(event.resourceId()) || event.occurredAt() == null || event.metadata() == null) {
            return null;
        }
        String status = event.metadata().get("status");
        String reason = event.metadata().get("reason");
        String latencyValue = event.metadata().get("latencyMs");
        if (!SAFE_STATUSES.contains(status) || reason == null || !reason.matches("[A-Z][A-Z0-9_.:-]{2,127}")
                || latencyValue == null) return null;
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
        return new SkillSearchProbeResult(index.backend(), status, reason, httpStatus, latency, event.occurredAt());
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
