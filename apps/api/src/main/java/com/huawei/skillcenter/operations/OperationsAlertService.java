package com.huawei.skillcenter.operations;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OperationsAlertService {
    private static final Set<String> SECURITY_EVENT_CODES = Set.of(
            "RATE_LIMITED", "CSRF_ORIGIN_REJECTED", "IDEMPOTENCY_REPLAY",
            "IDEMPOTENCY_CONFLICT", "EXPORT_QUEUE_FULL");

    private final Clock clock;
    private final OperationsMetricsService metricsService;
    private final double p95LatencyThresholdMs;
    private final double serverErrorRateThreshold;
    private final long minimumRequests;
    private final long securityEventThreshold;
    private final OperationsAlertNotificationSink notificationSink;
    private final Map<String, AlertState> states = new HashMap<>();

    @Autowired
    public OperationsAlertService(
            OperationsMetricsService metricsService,
            @Value("${skill-center.operations.alerts.p95-latency-ms:1000}") double p95LatencyThresholdMs,
            @Value("${skill-center.operations.alerts.server-error-rate:0.05}") double serverErrorRateThreshold,
            @Value("${skill-center.operations.alerts.minimum-requests:10}") long minimumRequests,
            @Value("${skill-center.operations.alerts.security-event-count:10}") long securityEventThreshold,
            OperationsAlertNotificationSink notificationSink) {
        this(Clock.systemUTC(), metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, notificationSink);
    }

    public OperationsAlertService(Clock clock, OperationsMetricsService metricsService,
                                  double p95LatencyThresholdMs, double serverErrorRateThreshold,
                                  long minimumRequests, long securityEventThreshold) {
        this(clock, metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, NoopOperationsAlertNotificationSink.INSTANCE);
    }

    public OperationsAlertService(Clock clock, OperationsMetricsService metricsService,
                                  double p95LatencyThresholdMs, double serverErrorRateThreshold,
                                  long minimumRequests, long securityEventThreshold,
                                  OperationsAlertNotificationSink notificationSink) {
        this.clock = clock;
        this.metricsService = metricsService;
        this.p95LatencyThresholdMs = Math.max(0, p95LatencyThresholdMs);
        this.serverErrorRateThreshold = Math.max(0, serverErrorRateThreshold);
        this.minimumRequests = Math.max(1, minimumRequests);
        this.securityEventThreshold = Math.max(1, securityEventThreshold);
        this.notificationSink = notificationSink == null
                ? NoopOperationsAlertNotificationSink.INSTANCE : notificationSink;
    }

    public synchronized List<OperationsAlertSnapshot> evaluate(OperationsWindow window) {
        OperationsMetricsSnapshot snapshot = metricsService.snapshot(window);
        Instant evaluatedAt = clock.instant();
        List<OperationsAlertSnapshot> result = new ArrayList<>();
        result.add(update("P95_LATENCY", null, snapshot.latency().p95Ms(), p95LatencyThresholdMs,
                "ms", snapshot.requests().total() > 0 && snapshot.latency().p95Ms() >= p95LatencyThresholdMs,
                evaluatedAt));
        double serverErrorRate = snapshot.requests().total() == 0 ? 0D
                : (double) snapshot.requests().serverErrors() / snapshot.requests().total();
        result.add(update("SERVER_ERROR_RATE", null, serverErrorRate, serverErrorRateThreshold,
                "ratio", snapshot.requests().total() >= minimumRequests
                        && serverErrorRate >= serverErrorRateThreshold, evaluatedAt));
        SECURITY_EVENT_CODES.stream().sorted().forEach(eventCode ->
                result.add(update("SECURITY_EVENT_COUNT", eventCode,
                        snapshot.securityEvents().getOrDefault(eventCode, 0L), securityEventThreshold,
                        "count", snapshot.securityEvents().getOrDefault(eventCode, 0L) >= securityEventThreshold,
                        evaluatedAt)));
        return result;
    }

    private OperationsAlertSnapshot update(String rule, String eventCode, double currentValue, double threshold,
                                           String unit, boolean triggered, Instant evaluatedAt) {
        String key = eventCode == null ? rule : rule + ":" + eventCode;
        AlertState previous = states.get(key);
        Instant firstTriggeredAt = triggered
                ? previous != null && previous.active() ? previous.firstTriggeredAt() : evaluatedAt
                : previous == null ? null : previous.firstTriggeredAt();
        states.put(key, new AlertState(triggered, firstTriggeredAt));
        OperationsAlertSnapshot snapshot = new OperationsAlertSnapshot(rule, eventCode,
                triggered ? "ACTIVE" : "RESOLVED",
                currentValue, threshold, unit, firstTriggeredAt, evaluatedAt);
        boolean transitioned = previous == null ? triggered : previous.active() != triggered;
        if (transitioned && (triggered || previous != null && previous.active())) {
            try {
                notificationSink.notify(snapshot);
            } catch (RuntimeException ignored) {
                // Notification failures are isolated from the alert API response.
            }
        }
        return snapshot;
    }

    private record AlertState(boolean active, Instant firstTriggeredAt) {
    }
}
