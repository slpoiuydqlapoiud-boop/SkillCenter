package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
