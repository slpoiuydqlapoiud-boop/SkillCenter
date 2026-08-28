package com.huawei.skillcenter.execution;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ExecutionEnvironmentService {
    private final ExecutionEnvironmentRepository store;
    private final GovernanceStore governanceStore;
    private final Clock clock;

    @Autowired
    public ExecutionEnvironmentService(ExecutionEnvironmentRepository store, GovernanceStore governanceStore) {
        this(store, governanceStore, Clock.systemUTC());
    }

    public ExecutionEnvironmentService(ExecutionEnvironmentRepository store, GovernanceStore governanceStore, Clock clock) {
        this.store = store;
        this.governanceStore = governanceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<ExecutionEnvironment> list(String kind, String status, Actor actor) {
        requireAdmin(actor);
        ExecutionEnvironmentKind parsedKind = kind == null || kind.isBlank() ? null : ExecutionEnvironmentKind.from(kind);
        ExecutionEnvironmentStatus parsedStatus = status == null || status.isBlank() ? null : ExecutionEnvironmentStatus.from(status);
        return store.findAll(parsedKind, parsedStatus);
    }

    public ExecutionEnvironment create(ExecutionEnvironmentCreateRequest request, Actor actor, String requestId) {
        requireAdmin(actor);
        if (request == null) throw new IllegalArgumentException("execution environment request must not be null");
        Instant now = clock.instant();
        ExecutionEnvironment created = new ExecutionEnvironment(request.environmentId(),
                ExecutionEnvironmentKind.from(request.kind()), request.version(), ExecutionEnvironmentStatus.ACTIVE,
                request.capabilities(), request.adapterProviderId(), request.configReference(),
                actor.userId(), now, actor.userId(), now);
        try {
            ExecutionEnvironment result = store.create(created);
            audit("EXECUTION_ENVIRONMENT_REGISTERED", result, actor, requestId,
                    Map.of("version", result.version(), "status", result.status().name()));
            return result;
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("already exists")) {
                throw new ExecutionEnvironmentConflictException(exception.getMessage());
            }
            throw exception;
        }
    }

    public ExecutionEnvironment changeStatus(String kind, String environmentId,
                                              ExecutionEnvironmentStatusRequest request,
                                              Actor actor, String requestId) {
        requireAdmin(actor);
        ExecutionEnvironmentKind parsedKind = ExecutionEnvironmentKind.from(kind);
        ExecutionEnvironment current = findInternal(parsedKind, environmentId);
        if (request == null) throw new IllegalArgumentException("status request must not be null");
        ExecutionEnvironmentStatus nextStatus = ExecutionEnvironmentStatus.from(request.status());
        Instant now = clock.instant();
        ExecutionEnvironment updated = current.withStatus(nextStatus, actor.userId(), now);
        store.replace(updated, current.revision());
        audit("EXECUTION_ENVIRONMENT_STATUS_CHANGED", updated, actor, requestId,
                Map.of("fromStatus", current.status().name(), "toStatus", nextStatus.name()));
        return updated;
    }

    public ExecutionEnvironment requireActive(ExecutionEnvironmentKind kind, String environmentId) {
        ExecutionEnvironment current = findInternal(kind, environmentId);
        if (current.status() != ExecutionEnvironmentStatus.ACTIVE) {
            throw new ExecutionEnvironmentStateConflictException(
                    "Execution environment is not active: " + current.businessKey());
        }
        return current;
    }

    public ExecutionEnvironmentSnapshot requireActiveSnapshot(ExecutionEnvironmentKind kind, String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            return ExecutionEnvironmentSnapshot.empty(kind);
        }
        return ExecutionEnvironmentSnapshot.from(requireActive(kind, environmentId));
    }

    private ExecutionEnvironment findInternal(ExecutionEnvironmentKind kind, String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            throw new ExecutionEnvironmentNotFoundException(kind, environmentId);
        }
        return store.find(kind, environmentId.trim())
                .orElseThrow(() -> new ExecutionEnvironmentNotFoundException(kind, environmentId.trim()));
    }

    private void audit(String action, ExecutionEnvironment environment, Actor actor, String requestId,
                       Map<String, String> metadata) {
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(), action,
                "EXECUTION_ENVIRONMENT", environment.businessKey(), actor.userId(), actor.role(),
                requestId, environment.updatedAt(), metadata));
    }

    private void requireAdmin(Actor actor) {
        RoleGuard.require(actor, Set.of("admin"));
    }
}
