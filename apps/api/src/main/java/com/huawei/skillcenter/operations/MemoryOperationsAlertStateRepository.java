package com.huawei.skillcenter.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** In-process fallback intended for local development and single-instance deployments. */
@Component
@ConditionalOnProperty(name = "skill-center.operations.alert-state-backend",
        havingValue = "memory", matchIfMissing = true)
public class MemoryOperationsAlertStateRepository implements OperationsAlertStateRepository {
    private final Map<String, OperationsAlertState> states = new HashMap<>();

    @Override
    public synchronized OperationsAlertStateTransition transition(String key, boolean active, Instant evaluatedAt) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (evaluatedAt == null) throw new IllegalArgumentException("evaluatedAt is required");
        OperationsAlertState previous = states.get(key);
        Instant firstTriggeredAt = active
                ? previous != null && previous.active() ? previous.firstTriggeredAt() : evaluatedAt
                : previous == null ? null : previous.firstTriggeredAt();
        OperationsAlertState current = new OperationsAlertState(active, firstTriggeredAt, evaluatedAt);
        boolean transitioned = previous == null ? active : previous.active() != active;
        states.put(key, current);
        return new OperationsAlertStateTransition(previous, current, transitioned);
    }

    @Override
    public OperationsAlertStateReadiness readiness() {
        return new OperationsAlertStateReadiness("memory", "DEGRADED",
                "OPERATIONS_ALERT_STATE_MEMORY_ONLY", "告警状态仅保存在当前进程");
    }
}
