package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsAlertEvaluationSchedulerTest {
    @Test
    void scheduledEvaluationUsesConfiguredWindow() {
        OperationsAlertService alerts = mock(OperationsAlertService.class);
        OperationsAlertEvaluationScheduler scheduler = new OperationsAlertEvaluationScheduler(
                alerts, "15m", readyState(), readyMetrics());

        scheduler.runOnce();

        verify(alerts).evaluate(OperationsWindow.FIFTEEN_MINUTES);
    }

    @Test
    void scheduledEvaluationContainsAlertFailures() {
        OperationsAlertService alerts = mock(OperationsAlertService.class);
        doThrow(new IllegalStateException("provider failed")).when(alerts).evaluate(OperationsWindow.FIVE_MINUTES);
        OperationsAlertEvaluationScheduler scheduler = new OperationsAlertEvaluationScheduler(
                alerts, "5m", readyState(), readyMetrics());

        assertThatCode(scheduler::runOnce).doesNotThrowAnyException();
    }

    @Test
    void invalidConfiguredWindowFailsClosedAtConstruction() {
        OperationsAlertService alerts = mock(OperationsAlertService.class);

        assertThatThrownBy(() -> new OperationsAlertEvaluationScheduler(alerts, "2h", readyState(), readyMetrics()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("window must be one of 5m, 15m or 60m");
    }

    @Test
    void schedulerRejectsProcessLocalAlertState() {
        OperationsAlertService alerts = mock(OperationsAlertService.class);
        OperationsAlertStateRepository state = mock(OperationsAlertStateRepository.class);
        when(state.readiness()).thenReturn(new OperationsAlertStateReadiness(
                "memory", "DEGRADED", "OPERATIONS_ALERT_STATE_MEMORY_ONLY", "仅进程内状态"));

        assertThatThrownBy(() -> new OperationsAlertEvaluationScheduler(alerts, "5m", state, readyMetrics()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scheduled operations alerts require READY Redis state");
    }

    @Test
    void schedulerRejectsProcessLocalMetricsEvenWhenAlertStateIsShared() {
        OperationsAlertService alerts = mock(OperationsAlertService.class);
        OperationsMetricsService metrics = mock(OperationsMetricsService.class);
        when(metrics.readiness()).thenReturn(new OperationsMetricsReadiness(
                "memory", "DISABLED", false, "OPERATIONS_METRICS_MEMORY_ONLY", "仅进程内指标"));

        assertThatThrownBy(() -> new OperationsAlertEvaluationScheduler(alerts, "5m", readyState(), metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scheduled operations alerts require READY Redis metrics");
    }

    private OperationsAlertStateRepository readyState() {
        OperationsAlertStateRepository state = mock(OperationsAlertStateRepository.class);
        when(state.readiness()).thenReturn(new OperationsAlertStateReadiness(
                "redis", "READY", "OPERATIONS_ALERT_STATE_REDIS_READY", "Redis ready"));
        return state;
    }

    private OperationsMetricsService readyMetrics() {
        OperationsMetricsService metrics = mock(OperationsMetricsService.class);
        when(metrics.readiness()).thenReturn(new OperationsMetricsReadiness(
                "redis", "READY", true, "OPERATIONS_METRICS_REDIS_READY", "Redis ready"));
        return metrics;
    }
}
