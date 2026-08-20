package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class WebhookOperationsAlertNotificationSink implements OperationsAlertNotificationSink {
    private final URI endpoint;
    private final ObjectMapper objectMapper;
    private final Sender sender;

    public WebhookOperationsAlertNotificationSink(URI endpoint, ObjectMapper objectMapper, Duration timeout) {
        this(endpoint, objectMapper, new HttpSender(HttpClient.newBuilder().connectTimeout(timeout).build(), timeout));
    }

    WebhookOperationsAlertNotificationSink(URI endpoint, ObjectMapper objectMapper, Sender sender) {
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
        this.sender = sender;
    }

    @Override
    public void notify(OperationsAlertSnapshot alert) {
        try {
            sender.send(endpoint, objectMapper.writeValueAsString(payload(alert)));
        } catch (JsonProcessingException | RuntimeException ignored) {
            // An unavailable notification sink must not fail the admin alert query.
        }
    }

    private Map<String, Object> payload(OperationsAlertSnapshot alert) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "skill-center.operations.alert");
        envelope.put("alert", alert);
        return envelope;
    }

    @FunctionalInterface
    interface Sender {
        void send(URI endpoint, String body);
    }

    private static final class HttpSender implements Sender {
        private final HttpClient client;
        private final Duration timeout;

        private HttpSender(HttpClient client, Duration timeout) {
            this.client = client;
            this.timeout = timeout;
        }

        @Override
        public void send(URI endpoint, String body) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ignored -> null);
        }
    }
}
