package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.InvocationEventStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RetentionService {
    private static final int PREVIEW_TTL_MINUTES = 10;
    private final GovernanceStore store;
    private final InvocationEventStore invocationEventStore;
    private final Map<String, RetentionPreview> previews = new ConcurrentHashMap<>();
    private final Map<String, RetentionExecutionResult> executions = new ConcurrentHashMap<>();

    public RetentionService(GovernanceStore store, InvocationEventStore invocationEventStore) {
        this.store = store;
        this.invocationEventStore = invocationEventStore;
    }

    public RetentionPolicy get(Actor actor) {
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        return store.snapshot().retentionPolicy();
    }

    public RetentionPolicy update(RetentionPolicyMutation request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) {
            throw new RetentionException("RETENTION_POLICY_INVALID", "Retention policy is required");
        }
        RetentionPolicy current = store.snapshot().retentionPolicy();
        if (request.policyVersion() != current.policyVersion()) {
            throw new RetentionException("RETENTION_POLICY_CONFLICT", "Retention policy version is stale");
        }
        RetentionPolicy next = new RetentionPolicy(current.policyVersion() + 1,
                request.auditRetentionDays(), request.invocationRetentionDays(),
                request.installationRetentionDays(), actor.userId(), OffsetDateTime.now(ZoneOffset.UTC));
        store.updateRetentionPolicy(next);
        audit("RETENTION_POLICY_UPDATED", actor, requestId, Map.of("policyVersion", String.valueOf(next.policyVersion())));
        return next;
    }

    public RetentionPreview preview(Actor actor, String requestId) {
        requireAdmin(actor);
        RetentionPolicy policy = store.snapshot().retentionPolicy();
        Instant now = Instant.now();
        Instant invocationCutoff = now.minus(policy.invocationRetentionDays(), ChronoUnit.DAYS);
        Instant installationCutoff = now.minus(policy.installationRetentionDays(), ChronoUnit.DAYS);
        Instant auditCutoff = now.minus(policy.auditRetentionDays(), ChronoUnit.DAYS);
        long auditEligible = store.snapshot().audits().stream()
                .filter(audit -> audit.occurredAt() != null && audit.occurredAt().isBefore(auditCutoff)).count();
        RetentionPreview preview = new RetentionPreview(UUID.randomUUID().toString(), policy.policyVersion(),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(PREVIEW_TTL_MINUTES), invocationCutoff,
                installationCutoff, auditCutoff, invocationEventStore.countBefore(invocationCutoff),
                store.countInstallationsBefore(installationCutoff), auditEligible,
                invocationEventStore.countBefore(invocationCutoff) * 512L
                        + store.countInstallationsBefore(installationCutoff) * 512L);
        previews.put(preview.previewId(), preview);
        audit("RETENTION_PREVIEW", actor, requestId, Map.of("policyVersion", String.valueOf(policy.policyVersion())));
        return preview;
    }

    public RetentionExecutionResult execute(RetentionExecutionRequest request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null || request.previewId() == null || request.previewId().isBlank()
                || request.executionId() == null || request.executionId().isBlank()) {
            throw new RetentionException("RETENTION_EXECUTION_CONFLICT", "Retention execution confirmation is invalid");
        }
        RetentionExecutionResult existing = executions.get(request.executionId());
        if (existing != null) {
            return new RetentionExecutionResult(existing.executionId(), existing.previewId(), existing.policyVersion(),
                    existing.invocationDeleted(), existing.installationDeleted(), existing.auditArchiveEligibleCount(), true);
        }
        RetentionPreview preview = previews.get(request.previewId());
        if (preview == null || !OffsetDateTime.now(ZoneOffset.UTC).isBefore(preview.expiresAt())) {
            throw new RetentionException("RETENTION_PREVIEW_EXPIRED", "Retention preview is expired");
        }
        if (request.policyVersion() != store.snapshot().retentionPolicy().policyVersion()
                || request.policyVersion() != preview.policyVersion()) {
            throw new RetentionException("RETENTION_EXECUTION_CONFLICT", "Retention policy changed after preview");
        }
        long invocationDeleted = invocationEventStore.deleteBefore(preview.invocationCutoff());
        long installationDeleted = store.deleteInstallationsBefore(preview.installationCutoff());
        RetentionExecutionResult result = new RetentionExecutionResult(request.executionId(), preview.previewId(),
                preview.policyVersion(), invocationDeleted, installationDeleted,
                preview.auditArchiveEligibleCount(), false);
        executions.put(request.executionId(), result);
        audit("RETENTION_EXECUTED", actor, requestId, Map.of("executionId", request.executionId(),
                "invocationDeleted", String.valueOf(invocationDeleted),
                "installationDeleted", String.valueOf(installationDeleted)));
        return result;
    }

    private void requireAdmin(Actor actor) {
        if (actor == null || !"admin".equals(actor.role())) {
            throw new RetentionException("FORBIDDEN", "Only admin can modify or execute retention policy");
        }
    }

    private void audit(String action, Actor actor, String requestId, Map<String, String> metadata) {
        store.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "RETENTION", "policy",
                actor.userId(), actor.role(), requestId == null ? UUID.randomUUID().toString() : requestId,
                Instant.now(), metadata));
    }
}
