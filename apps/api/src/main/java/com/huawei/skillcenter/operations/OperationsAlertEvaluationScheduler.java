package com.huawei.skillcenter.operations;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically evaluates persisted operations alerts when explicitly enabled for a deployment. */
@Component
@ConditionalOnProperty(name = "skill-center.operations.alerts.scheduler-enabled", havingValue = "true")
public class OperationsAlertEvaluationScheduler {
    private final OperationsAlertService alertService;
    private final OperationsWindow window;
    private final OperationsMetricsService metricsService;

    @Autowired
    public OperationsAlertEvaluationScheduler(
            OperationsAlertService alertService,
            @Value("${skill-center.operations.alerts.evaluation-window:5m}") String configuredWindow,
            OperationsAlertStateRepository stateRepository,
            OperationsMetricsService metricsService) {
        if (alertService == null) {
            throw new IllegalArgumentException("alertService is required");
        }
        if (stateRepository == null) {
            throw new IllegalStateException("scheduled operations alerts require READY Redis state");
        }
        if (metricsService == null) {
            throw new IllegalStateException("scheduled operations alerts require READY Redis metrics");
        }
        this.alertService = alertService;
        this.window = OperationsWindow.parse(configuredWindow);
        this.metricsService = metricsService;
        OperationsAlertStateReadiness readiness;
        try {
            readiness = stateRepository.readiness();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("scheduled operations alerts require READY Redis state", exception);
        }
        if (readiness == null || !"redis".equalsIgnoreCase(readiness.backend())
                || !"READY".equalsIgnoreCase(readiness.status())) {
            throw new IllegalStateException("scheduled operations alerts require READY Redis state");
        }
        OperationsMetricsReadiness metricsReadiness;
        try {
            metricsReadiness = metricsService.readiness();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("scheduled operations alerts require READY Redis metrics", exception);
        }
        if (metricsReadiness == null || !metricsReadiness.shared()
                || !"redis".equalsIgnoreCase(metricsReadiness.backend())
                || !"READY".equalsIgnoreCase(metricsReadiness.status())) {
            throw new IllegalStateException("scheduled operations alerts require READY Redis metrics");
        }
    }

    @Scheduled(
            fixedDelayString = "${skill-center.operations.alerts.evaluation-interval-ms:60000}",
            initialDelayString = "${skill-center.operations.alerts.evaluation-initial-delay-ms:60000}")
    public void evaluateScheduled() {
        runOnce();
    }

    void runOnce() {
        try {
            alertService.evaluate(window);
        } catch (RuntimeException ignored) {
            // A failed evaluation must not stop the scheduler or leak provider details.
        }
    }
}
