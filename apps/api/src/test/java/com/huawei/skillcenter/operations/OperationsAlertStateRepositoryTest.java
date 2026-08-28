package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsAlertStateRepositoryTest {
    private static final Instant FIRST = Instant.parse("2026-08-25T00:00:00Z");
    private static final Instant SECOND = FIRST.plusSeconds(30);

    @Test
    void firstActiveEvaluationCreatesStateAndMarksTransition() {
        MemoryOperationsAlertStateRepository repository = new MemoryOperationsAlertStateRepository();

        OperationsAlertStateTransition transition = repository.transition("SERVER_ERROR_RATE", true, FIRST);

        assertThat(transition.previous()).isNull();
        assertThat(transition.transitioned()).isTrue();
        assertThat(transition.current().active()).isTrue();
        assertThat(transition.current().firstTriggeredAt()).isEqualTo(FIRST);
        assertThat(transition.current().lastEvaluatedAt()).isEqualTo(FIRST);
    }

    @Test
    void repeatedActiveEvaluationPreservesFirstTriggerWithoutTransition() {
        MemoryOperationsAlertStateRepository repository = new MemoryOperationsAlertStateRepository();
        repository.transition("SERVER_ERROR_RATE", true, FIRST);

        OperationsAlertStateTransition transition = repository.transition("SERVER_ERROR_RATE", true, SECOND);

        assertThat(transition.previous().active()).isTrue();
        assertThat(transition.transitioned()).isFalse();
        assertThat(transition.current().firstTriggeredAt()).isEqualTo(FIRST);
        assertThat(transition.current().lastEvaluatedAt()).isEqualTo(SECOND);
    }

    @Test
    void activeToResolvedPreservesFirstTriggerAndMarksTransition() {
        MemoryOperationsAlertStateRepository repository = new MemoryOperationsAlertStateRepository();
        repository.transition("SERVER_ERROR_RATE", true, FIRST);

        OperationsAlertStateTransition transition = repository.transition("SERVER_ERROR_RATE", false, SECOND);

        assertThat(transition.transitioned()).isTrue();
        assertThat(transition.current().active()).isFalse();
        assertThat(transition.current().firstTriggeredAt()).isEqualTo(FIRST);
        assertThat(transition.current().lastEvaluatedAt()).isEqualTo(SECOND);
    }

    @Test
    void firstResolvedEvaluationDoesNotCreateNotificationTransition() {
        MemoryOperationsAlertStateRepository repository = new MemoryOperationsAlertStateRepository();

        OperationsAlertStateTransition transition = repository.transition("P95_LATENCY", false, FIRST);

        assertThat(transition.previous()).isNull();
        assertThat(transition.transitioned()).isFalse();
        assertThat(transition.current().active()).isFalse();
        assertThat(transition.current().firstTriggeredAt()).isNull();
    }

    @Test
    void memoryReadinessExplainsSingleInstanceMode() {
        OperationsAlertStateReadiness readiness = new MemoryOperationsAlertStateRepository().readiness();

        assertThat(readiness.backend()).isEqualTo("memory");
        assertThat(readiness.status()).isEqualTo("DEGRADED");
        assertThat(readiness.reasonCode()).isEqualTo("OPERATIONS_ALERT_STATE_MEMORY_ONLY");
    }
}
