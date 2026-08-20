package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

@Configuration
public class OperationsAlertNotificationConfiguration {
    @Bean
    OperationsAlertNotificationSink operationsAlertNotificationSink(
            @Value("${skill-center.operations.alerts.notification-url:}") String notificationUrl,
            @Value("${skill-center.operations.alerts.notification-timeout-ms:1500}") long timeoutMs,
            ObjectMapper objectMapper) {
        if (notificationUrl == null || notificationUrl.isBlank()) {
            return NoopOperationsAlertNotificationSink.INSTANCE;
        }
        try {
            URI endpoint = URI.create(notificationUrl);
            if (!"http".equalsIgnoreCase(endpoint.getScheme())
                    && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                return NoopOperationsAlertNotificationSink.INSTANCE;
            }
            return new WebhookOperationsAlertNotificationSink(endpoint, objectMapper,
                    Duration.ofMillis(Math.max(100, timeoutMs)));
        } catch (IllegalArgumentException invalidUrl) {
            return NoopOperationsAlertNotificationSink.INSTANCE;
        }
    }
}
