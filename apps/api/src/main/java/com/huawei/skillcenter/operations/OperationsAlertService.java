package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionReconciliation;
import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class OperationsAlertService {
    private static final Set<String> SECURITY_EVENT_CODES = Set.of(
            "RATE_LIMITED", "CSRF_ORIGIN_REJECTED", "IDEMPOTENCY_REPLAY",
            "IDEMPOTENCY_CONFLICT", "EXPORT_QUEUE_FULL");
    private static final Set<String> LIFECYCLE_PROJECTION_REASON_CODES = Set.of(
            "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED",
            "SKILL_LIFECYCLE_PROJECTION_COUNT_MISMATCH",
            "SKILL_LIFECYCLE_PROJECTION_SOURCE_STALE",
            "SKILL_LIFECYCLE_PROJECTION_NOT_IMPORTED",
            "SKILL_LIFECYCLE_PROJECTION_NOT_READY");

    private final Clock clock;
    private final OperationsMetricsService metricsService;
    private final double p95LatencyThresholdMs;
    private final double serverErrorRateThreshold;
    private final long minimumRequests;
    private final long securityEventThreshold;
    private final OperationsAlertNotificationSink notificationSink;
    private final Supplier<SkillLifecycleProjectionReconciliation> lifecycleProjectionProbe;
    private final Supplier<OptimizationWorkItemStaleness> optimizationWorkItemProbe;
    private final Supplier<ProductionEvidenceReadiness> productionEvidenceProbe;
    private final Supplier<QualityRegressionHealth> qualityRegressionProbe;
    private final OperationsAlertStateRepository stateRepository;

    @Autowired
    public OperationsAlertService(
            OperationsMetricsService metricsService,
            @Value("${skill-center.operations.alerts.p95-latency-ms:1000}") double p95LatencyThresholdMs,
            @Value("${skill-center.operations.alerts.server-error-rate:0.05}") double serverErrorRateThreshold,
            @Value("${skill-center.operations.alerts.minimum-requests:10}") long minimumRequests,
            @Value("${skill-center.operations.alerts.security-event-count:10}") long securityEventThreshold,
            OperationsAlertNotificationSink notificationSink,
            SkillLifecycleProjectionService lifecycleProjectionService,
            OperationsAlertStateRepository stateRepository,
            ObjectProvider<OptimizationWorkItemStalenessProbe> optimizationWorkItemProbe,
            ObjectProvider<ProductionEvidenceService> productionEvidenceService,
            ObjectProvider<QualityRegressionProbe> qualityRegressionProbe) {
        this(Clock.systemUTC(), metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, notificationSink,
                lifecycleProjectionService == null ? null : lifecycleProjectionService::reconciliation,
                stateRepository, optimizationWorkItemProbe == null || optimizationWorkItemProbe.getIfAvailable() == null
                        ? null : optimizationWorkItemProbe.getIfAvailable()::health,
                productionEvidenceService == null || productionEvidenceService.getIfAvailable() == null
                        ? null : productionEvidenceService.getIfAvailable()::evaluate,
                qualityRegressionProbe == null || qualityRegressionProbe.getIfAvailable() == null
                        ? null : qualityRegressionProbe.getIfAvailable()::health);
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
        this(clock, metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, notificationSink, null,
                new MemoryOperationsAlertStateRepository(), null, null);
    }

    public OperationsAlertService(Clock clock, OperationsMetricsService metricsService,
                                  double p95LatencyThresholdMs, double serverErrorRateThreshold,
                                  long minimumRequests, long securityEventThreshold,
                                  OperationsAlertNotificationSink notificationSink,
                                  Supplier<SkillLifecycleProjectionReconciliation> lifecycleProjectionProbe) {
        this(clock, metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, notificationSink, lifecycleProjectionProbe,
                new MemoryOperationsAlertStateRepository(), null, null);
    }

    public OperationsAlertService(Clock clock, OperationsMetricsService metricsService,
                                  double p95LatencyThresholdMs, double serverErrorRateThreshold,
                                  long minimumRequests, long securityEventThreshold,
                                  OperationsAlertNotificationSink notificationSink,
                                  Supplier<SkillLifecycleProjectionReconciliation> lifecycleProjectionProbe,
                                  OperationsAlertStateRepository stateRepository) {
        this(clock, metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, notificationSink, lifecycleProjectionProbe,
                stateRepository, null, null);
    }

    public OperationsAlertService(Clock clock, OperationsMetricsService metricsService,
                                  double p95LatencyThresholdMs, double serverErrorRateThreshold,
                                  long minimumRequests, long securityEventThreshold,
                                  OperationsAlertNotificationSink notificationSink,
                                  Supplier<SkillLifecycleProjectionReconciliation> lifecycleProjectionProbe,
                                  OperationsAlertStateRepository stateRepository,
                                  Supplier<OptimizationWorkItemStaleness> optimizationWorkItemProbe) {
        this(clock, metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, notificationSink, lifecycleProjectionProbe,
                stateRepository, optimizationWorkItemProbe, null);
    }

    public OperationsAlertService(Clock clock, OperationsMetricsService metricsService,
                                  double p95LatencyThresholdMs, double serverErrorRateThreshold,
                                  long minimumRequests, long securityEventThreshold,
                                  OperationsAlertNotificationSink notificationSink,
                                  Supplier<SkillLifecycleProjectionReconciliation> lifecycleProjectionProbe,
                                  OperationsAlertStateRepository stateRepository,
                                  Supplier<OptimizationWorkItemStaleness> optimizationWorkItemProbe,
                                  Supplier<ProductionEvidenceReadiness> productionEvidenceProbe) {
        this(clock, metricsService, p95LatencyThresholdMs, serverErrorRateThreshold,
                minimumRequests, securityEventThreshold, notificationSink, lifecycleProjectionProbe,
                stateRepository, optimizationWorkItemProbe, productionEvidenceProbe, null);
    }

    public OperationsAlertService(Clock clock, OperationsMetricsService metricsService,
                                  double p95LatencyThresholdMs, double serverErrorRateThreshold,
                                  long minimumRequests, long securityEventThreshold,
                                  OperationsAlertNotificationSink notificationSink,
                                  Supplier<SkillLifecycleProjectionReconciliation> lifecycleProjectionProbe,
                                  OperationsAlertStateRepository stateRepository,
                                  Supplier<OptimizationWorkItemStaleness> optimizationWorkItemProbe,
                                  Supplier<ProductionEvidenceReadiness> productionEvidenceProbe,
                                  Supplier<QualityRegressionHealth> qualityRegressionProbe) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.metricsService = metricsService;
        this.p95LatencyThresholdMs = Math.max(0, p95LatencyThresholdMs);
        this.serverErrorRateThreshold = Math.max(0, serverErrorRateThreshold);
        this.minimumRequests = Math.max(1, minimumRequests);
        this.securityEventThreshold = Math.max(1, securityEventThreshold);
        this.notificationSink = notificationSink == null
                ? NoopOperationsAlertNotificationSink.INSTANCE : notificationSink;
        this.lifecycleProjectionProbe = lifecycleProjectionProbe;
        this.optimizationWorkItemProbe = optimizationWorkItemProbe;
        this.productionEvidenceProbe = productionEvidenceProbe;
        this.qualityRegressionProbe = qualityRegressionProbe;
        this.stateRepository = stateRepository == null
                ? new MemoryOperationsAlertStateRepository() : stateRepository;
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
        if (lifecycleProjectionProbe != null) {
            String lifecycleReasonCode = lifecycleProjectionReasonCode();
            LIFECYCLE_PROJECTION_REASON_CODES.stream().sorted().forEach(reasonCode ->
                    result.add(update("SKILL_LIFECYCLE_PROJECTION", reasonCode,
                            reasonCode.equals(lifecycleReasonCode) ? 1D : 0D, 1D, "state",
                            reasonCode.equals(lifecycleReasonCode), evaluatedAt)));
        }
        if (optimizationWorkItemProbe != null) {
            result.add(optimizationWorkItemAlert(evaluatedAt));
        }
        if (productionEvidenceProbe != null) {
            result.add(productionEvidenceAlert(evaluatedAt));
        }
        if (qualityRegressionProbe != null) {
            result.add(qualityRegressionAlert(evaluatedAt));
        }
        return result;
    }

    private OperationsAlertSnapshot qualityRegressionAlert(Instant evaluatedAt) {
        QualityRegressionHealth health;
        try {
            health = qualityRegressionProbe.get();
        } catch (RuntimeException ignored) {
            health = null;
        }
        boolean triggered = health == null || !"HEALTHY".equals(health.status()) || health.regressionCount() > 0;
        String eventCode = health == null ? "QUALITY_REGRESSION_SIGNAL_UNAVAILABLE" : health.reasonCode();
        double current = health == null ? 0 : health.regressionCount();
        return update("QUALITY_REGRESSION", "QUALITY_REGRESSION", eventCode,
                current, 0, "count", triggered, evaluatedAt);
    }

    private OperationsAlertSnapshot productionEvidenceAlert(Instant evaluatedAt) {
        ProductionEvidenceReadiness readiness;
        try {
            readiness = productionEvidenceProbe.get();
        } catch (RuntimeException ignored) {
            readiness = null;
        }
        boolean triggered = readiness == null || !"READY".equals(readiness.status());
        String eventCode = readiness == null ? "PRODUCTION_EVIDENCE_STORE_UNAVAILABLE" : readiness.reasonCode();
        double current = readiness == null
                ? 0 : Math.max(0, readiness.requiredCount() - readiness.acceptedCount());
        return update("PRODUCTION_HANDOFF_EVIDENCE", "PRODUCTION_HANDOFF_EVIDENCE", eventCode,
                current, 0, "count", triggered, evaluatedAt);
    }

    private OperationsAlertSnapshot optimizationWorkItemAlert(Instant evaluatedAt) {
        OptimizationWorkItemStaleness health;
        try {
            health = optimizationWorkItemProbe.get();
        } catch (RuntimeException ignored) {
            health = null;
        }
        boolean triggered = health == null || !"HEALTHY".equals(health.status()) || health.staleCount() > 0;
        String eventCode = health == null ? "OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE" : health.reasonCode();
        double current = health == null ? 0 : health.staleCount();
        return update("OPTIMIZATION_WORK_ITEM_STALENESS", "OPTIMIZATION_WORK_ITEM_STALENESS", eventCode,
                current, 0, "count", triggered, evaluatedAt);
    }

    private String lifecycleProjectionReasonCode() {
        try {
            SkillLifecycleProjectionReconciliation reconciliation = lifecycleProjectionProbe.get();
            if (reconciliation == null) return "SKILL_LIFECYCLE_PROJECTION_NOT_READY";
            if ("HEALTHY".equals(reconciliation.state()) || "LIVE_SOURCE".equals(reconciliation.state())) {
                return null;
            }
            return LIFECYCLE_PROJECTION_REASON_CODES.contains(reconciliation.reasonCode())
                    ? reconciliation.reasonCode() : "SKILL_LIFECYCLE_PROJECTION_NOT_READY";
        } catch (RuntimeException ignored) {
            return "SKILL_LIFECYCLE_PROJECTION_NOT_READY";
        }
    }

    private OperationsAlertSnapshot update(String rule, String eventCode, double currentValue, double threshold,
                                           String unit, boolean triggered, Instant evaluatedAt) {
        String key = eventCode == null ? rule : rule + ":" + eventCode;
        return update(key, rule, eventCode, currentValue, threshold, unit, triggered, evaluatedAt);
    }

    private OperationsAlertSnapshot update(String stateKey, String rule, String eventCode, double currentValue,
                                           double threshold, String unit, boolean triggered, Instant evaluatedAt) {
        OperationsAlertStateTransition transition = stateRepository.transition(stateKey, triggered, evaluatedAt);
        OperationsAlertState current = transition.current();
        Instant firstTriggeredAt = current.firstTriggeredAt();
        OperationsAlertSnapshot snapshot = new OperationsAlertSnapshot(rule, eventCode,
                triggered ? "ACTIVE" : "RESOLVED",
                currentValue, threshold, unit, firstTriggeredAt, evaluatedAt);
        if (transition.transitioned() && (triggered
                || transition.previous() != null && transition.previous().active())) {
            try {
                notificationSink.notify(snapshot);
            } catch (RuntimeException ignored) {
                // Notification failures are isolated from the alert API response.
            }
        }
        return snapshot;
    }
}
