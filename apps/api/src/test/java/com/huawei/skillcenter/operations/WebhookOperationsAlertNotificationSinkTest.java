package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookOperationsAlertNotificationSinkTest {
    @Test
    void sendsAggregateOnlyPayloadWithoutSecrets() throws Exception {
        CapturingSender sender = new CapturingSender();
        WebhookOperationsAlertNotificationSink sink = new WebhookOperationsAlertNotificationSink(
                URI.create("https://alerts.internal/hook"), new ObjectMapper().findAndRegisterModules(), sender);
        OperationsAlertSnapshot alert = new OperationsAlertSnapshot(
                "SERVER_ERROR_RATE", null, "ACTIVE", 0.6, 0.5, "ratio",
                Instant.parse("2026-08-18T06:00:10Z"), Instant.parse("2026-08-18T06:00:20Z"));

        sink.notify(alert);

        assertThat(sender.uri).isEqualTo(URI.create("https://alerts.internal/hook"));
        assertThat(sender.body).contains("SERVER_ERROR_RATE", "ACTIVE", "0.6")
                .doesNotContain("token", "prompt", "output", "userId", "requestBody");
    }

    private static final class CapturingSender implements WebhookOperationsAlertNotificationSink.Sender {
        private URI uri;
        private String body;

        @Override
        public void send(URI uri, String body) {
            this.uri = uri;
            this.body = body;
        }
    }
}
