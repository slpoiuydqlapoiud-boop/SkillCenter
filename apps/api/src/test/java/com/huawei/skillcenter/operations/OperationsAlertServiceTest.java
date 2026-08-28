package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionCountDelta;
import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionCounts;
import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionReconciliation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsAlertServiceTest {
    @Test
    void evaluatesActiveAndResolvedLatencyErrorAndSecurityAlerts() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2);

        metrics.recordRequest(500, 1_500);
        metrics.recordRequest(200, 20);
        metrics.recordSecurityEvent("RATE_LIMITED");
        metrics.recordSecurityEvent("RATE_LIMITED");

        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("P95_LATENCY");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                })
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("SERVER_ERROR_RATE");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                })
                .anySatisfy(alert -> {
                    assertThat(alert.eventCode()).isEqualTo("RATE_LIMITED");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                });

        clock.advanceSeconds(6 * 60);
        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .allSatisfy(alert -> assertThat(alert.status()).isEqualTo("RESOLVED"));
    }

    @Test
    void notifiesOnlyOnAlertStateTransitions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        RecordingSink sink = new RecordingSink();
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2, sink);
        metrics.recordRequest(500, 1_500);
        metrics.recordRequest(200, 20);

        alerts.evaluate(OperationsWindow.FIVE_MINUTES);
        alerts.evaluate(OperationsWindow.FIVE_MINUTES);
        assertThat(sink.notifications).extracting(OperationsAlertSnapshot::rule)
                .containsExactly("P95_LATENCY", "SERVER_ERROR_RATE");

        clock.advanceSeconds(6 * 60);
        alerts.evaluate(OperationsWindow.FIVE_MINUTES);
        assertThat(sink.notifications).extracting(OperationsAlertSnapshot::status)
                .containsExactly("ACTIVE", "ACTIVE", "RESOLVED", "RESOLVED");
    }

    @Test
    void sharedStateRepositoryPreventsDuplicateActiveNotificationAcrossServiceInstances() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        RecordingSink sink = new RecordingSink();
        MemoryOperationsAlertStateRepository stateRepository = new MemoryOperationsAlertStateRepository();
        OperationsAlertService first = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                sink, null, stateRepository);
        OperationsAlertService second = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                sink, null, stateRepository);
        metrics.recordRequest(500, 1_500);
        metrics.recordRequest(200, 20);

        first.evaluate(OperationsWindow.FIVE_MINUTES);
        second.evaluate(OperationsWindow.FIVE_MINUTES);

        assertThat(sink.notifications).extracting(OperationsAlertSnapshot::status)
                .containsExactly("ACTIVE", "ACTIVE");
    }

    @Test
    void emitsLifecycleProjectionAlertAndResolvesAfterHealthyState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        RecordingSink sink = new RecordingSink();
        AtomicReference<SkillLifecycleProjectionReconciliation> reconciliation = new AtomicReference<>(
                reconciliation("DRIFTED", "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED"));
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                sink, reconciliation::get);

        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("SKILL_LIFECYCLE_PROJECTION");
                    assertThat(alert.eventCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                    assertThat(alert.unit()).isEqualTo("state");
                });

        reconciliation.set(reconciliation("HEALTHY", ""));
        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("SKILL_LIFECYCLE_PROJECTION");
                    assertThat(alert.eventCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED");
                    assertThat(alert.status()).isEqualTo("RESOLVED");
                });
        assertThat(sink.notifications).extracting(OperationsAlertSnapshot::rule)
                .contains("SKILL_LIFECYCLE_PROJECTION");
        assertThat(sink.notifications).extracting(OperationsAlertSnapshot::status)
                .contains("ACTIVE", "RESOLVED");
    }

    @Test
    void convertsLifecycleProjectionProbeFailureIntoNotReadyAlert() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                NoopOperationsAlertNotificationSink.INSTANCE, () -> {
                    throw new IllegalStateException("projection unavailable");
                });

        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("SKILL_LIFECYCLE_PROJECTION");
                    assertThat(alert.eventCode()).isEqualTo("SKILL_LIFECYCLE_PROJECTION_NOT_READY");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                });
    }

    @Test
    void emitsAndResolvesOptimizationWorkItemStalenessAlert() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        RecordingSink sink = new RecordingSink();
        AtomicReference<OptimizationWorkItemStaleness> health = new AtomicReference<>(
                new OptimizationWorkItemStaleness("DEGRADED", "OPTIMIZATION_WORK_ITEMS_STALE",
                        clock.instant(), 604800, 2, 1, java.util.Map.of("OPEN", 1),
                        java.util.Map.of("owner-a", 1), java.util.Map.of("HIGH", 1), List.of()));
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                sink, null, new MemoryOperationsAlertStateRepository(), health::get);

        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("OPTIMIZATION_WORK_ITEM_STALENESS");
                    assertThat(alert.eventCode()).isEqualTo("OPTIMIZATION_WORK_ITEMS_STALE");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                    assertThat(alert.currentValue()).isEqualTo(1);
                });

        health.set(new OptimizationWorkItemStaleness("HEALTHY", "OPTIMIZATION_WORK_ITEMS_HEALTHY",
                clock.instant(), 604800, 2, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), List.of()));
        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("OPTIMIZATION_WORK_ITEM_STALENESS");
                    assertThat(alert.status()).isEqualTo("RESOLVED");
        });
    }

    @Test
    void emitsAndResolvesProductionEvidenceReadinessAlert() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        RecordingSink sink = new RecordingSink();
        AtomicReference<ProductionEvidenceReadiness> readiness = new AtomicReference<>(
                new ProductionEvidenceReadiness("NOT_READY", "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED",
                        9, 7, List.of("PRODUCTION_EVIDENCE_BACKUP_PITR_EXPIRED")));
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                sink, null, new MemoryOperationsAlertStateRepository(), null, readiness::get);

        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("PRODUCTION_HANDOFF_EVIDENCE");
                    assertThat(alert.eventCode()).isEqualTo("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                    assertThat(alert.currentValue()).isEqualTo(2);
                    assertThat(alert.unit()).isEqualTo("count");
                });

        readiness.set(new ProductionEvidenceReadiness("READY", "PRODUCTION_EXTERNAL_EVIDENCE_READY",
                9, 9, List.of()));
        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("PRODUCTION_HANDOFF_EVIDENCE");
                    assertThat(alert.status()).isEqualTo("RESOLVED");
        });
    }

    @Test
    void emitsAndResolvesQualityRegressionAlertUsingStableStateKey() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        RecordingSink sink = new RecordingSink();
        AtomicReference<QualityRegressionHealth> health = new AtomicReference<>(
                new QualityRegressionHealth("DEGRADED", "QUALITY_REGRESSIONS_DETECTED", clock.instant(),
                        3, 1, List.of(new QualityRegressionHealth.Regression(
                        "skill-a", "1.0.0", "1.1.0", "assessment-1", "REGRESSION", "CANDIDATE_REGRESSION"))));
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                sink, null, new MemoryOperationsAlertStateRepository(), null, null, health::get);

        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("QUALITY_REGRESSION");
                    assertThat(alert.eventCode()).isEqualTo("QUALITY_REGRESSIONS_DETECTED");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                    assertThat(alert.currentValue()).isEqualTo(1);
                    assertThat(alert.threshold()).isZero();
                });

        health.set(QualityRegressionHealth.healthy(clock.instant(), 3));
        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("QUALITY_REGRESSION");
                    assertThat(alert.eventCode()).isEqualTo("QUALITY_REGRESSIONS_HEALTHY");
                    assertThat(alert.status()).isEqualTo("RESOLVED");
                });
        assertThat(sink.notifications).extracting(OperationsAlertSnapshot::status)
                .containsExactly("ACTIVE", "RESOLVED");
    }

    @Test
    void convertsQualityRegressionProbeFailureIntoUnavailableAlert() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T06:00:10Z"));
        OperationsMetricsService metrics = new OperationsMetricsService(clock, "", false);
        OperationsAlertService alerts = new OperationsAlertService(clock, metrics, 1_000, 0.5, 2, 2,
                NoopOperationsAlertNotificationSink.INSTANCE, null, new MemoryOperationsAlertStateRepository(),
                null, null, () -> { throw new IllegalStateException("assessment store unavailable"); });

        assertThat(alerts.evaluate(OperationsWindow.FIVE_MINUTES))
                .anySatisfy(alert -> {
                    assertThat(alert.rule()).isEqualTo("QUALITY_REGRESSION");
                    assertThat(alert.eventCode()).isEqualTo("QUALITY_REGRESSION_SIGNAL_UNAVAILABLE");
                    assertThat(alert.status()).isEqualTo("ACTIVE");
                });
    }

    private static SkillLifecycleProjectionReconciliation reconciliation(String state, String reasonCode) {
        SkillLifecycleProjectionCounts counts = new SkillLifecycleProjectionCounts(1, 1, 1, 1, 1);
        SkillLifecycleProjectionCountDelta delta = "HEALTHY".equals(state)
                ? new SkillLifecycleProjectionCountDelta(0, 0, 0, 0, 0)
                : new SkillLifecycleProjectionCountDelta(1, 0, 0, 0, 0);
        Instant observedAt = Instant.parse("2026-08-18T06:00:00Z");
        return new SkillLifecycleProjectionReconciliation(
                "postgresql", state, reasonCode, "1", 7,
                "a".repeat(64), "b".repeat(64), observedAt, observedAt, observedAt,
                0L, 900L, counts, counts, delta);
    }

    private static final class RecordingSink implements OperationsAlertNotificationSink {
        private final List<OperationsAlertSnapshot> notifications = new ArrayList<>();

        @Override
        public void notify(OperationsAlertSnapshot alert) {
            notifications.add(alert);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
