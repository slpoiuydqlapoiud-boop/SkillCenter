package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.ProviderAdapterConfig;
import com.huawei.skillcenter.quality.ProviderCredentialResolver;
import com.huawei.skillcenter.quality.ProviderHealth;
import com.huawei.skillcenter.quality.ProviderHttpRequest;
import com.huawei.skillcenter.quality.ProviderHttpResponse;
import com.huawei.skillcenter.quality.ProviderHttpTransport;
import com.huawei.skillcenter.quality.ProviderUnavailableException;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Langfuse trace query adapter; only redacted trace metadata may cross this boundary. */
public final class LangfuseTraceProviderAdapter implements TraceProvider {
    private static final String ID = "langfuse-observability";
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "traceId", "spanId", "skillId", "version", "operation", "status", "durationMs",
            "errorCode", "dataSource", "occurredAt", "runtimeId", "mcpServerId", "llmProviderId");

    private final ProviderAdapterConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ObjectMapper mapper;

    public LangfuseTraceProviderAdapter(ProviderAdapterConfig config, ProviderHttpTransport transport,
                                        ProviderCredentialResolver credentials, ObjectMapper mapper) {
        this.config = config == null ? ProviderAdapterConfig.disabled() : config;
        this.transport = transport == null ? new com.huawei.skillcenter.quality.JavaHttpProviderTransport() : transport;
        this.credentials = credentials == null
                ? new com.huawei.skillcenter.quality.EnvironmentProviderCredentialResolver() : credentials;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public String providerVersion() {
        return "contract-v1";
    }

    @Override
    public List<String> capabilities() {
        return List.of("trace-metadata", "failure-location");
    }

    public ProviderHealth health() {
        if (!config.configured()) return ProviderHealth.notConfigured();
        if (!config.httpEnabled()) return ProviderHealth.contractOnly();
        return credentialAvailable() ? ProviderHealth.up() : ProviderHealth.notConfigured();
    }

    @Override
    public List<TraceObservation> query(TraceQuery query) {
        TraceQuery resolved = query == null
                ? new TraceQuery(RuntimeOperationsWindow.TWENTY_FOUR_HOURS, null, null, null, null, null)
                : query;
        if (!config.configured()) {
            throw new ProviderUnavailableException(ID, "EXTERNAL_ADAPTER_NOT_CONFIGURED");
        }
        if (!config.httpEnabled()) {
            throw new ProviderUnavailableException(ID, "EXTERNAL_ADAPTER_NOT_ENABLED");
        }
        String credential;
        try {
            credential = credentials.resolve(config.credentialRef());
        } catch (ProviderUnavailableException exception) {
            throw new ProviderUnavailableException(ID, "EXTERNAL_ADAPTER_NOT_CONFIGURED");
        }
        try {
            String body = mapper.writeValueAsString(requestBody(resolved));
            ProviderHttpResponse response = transport.post(new ProviderHttpRequest(ID, URI.create(config.endpoint()),
                    credential, body, Duration.ofSeconds(10)));
            ensureSuccess(response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("trace response must be an array");
            }
            List<TraceObservation> observations = new ArrayList<>();
            root.forEach(node -> observations.add(parseObservation(node)));
            return observations.stream()
                    .sorted(Comparator.comparing(TraceObservation::occurredAt).reversed()
                            .thenComparing(TraceObservation::spanId))
                    .toList();
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException | java.time.DateTimeException exception) {
            throw new ProviderUnavailableException(ID, "UPSTREAM_INVALID_RESPONSE");
        }
    }

    private Map<String, Object> requestBody(TraceQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("windowSeconds", query.window().seconds());
        body.put("now", query.effectiveNow().toString());
        putIfPresent(body, "skillId", query.skillId());
        putIfPresent(body, "version", query.version());
        putIfPresent(body, "traceId", query.traceId());
        putIfPresent(body, "status", query.status());
        putIfPresent(body, "dataSource", query.dataSource());
        putIfPresent(body, "runtimeId", query.runtimeId());
        putIfPresent(body, "mcpServerId", query.mcpServerId());
        putIfPresent(body, "llmProviderId", query.llmProviderId());
        return body;
    }

    private void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) body.put(key, value);
    }

    private TraceObservation parseObservation(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("trace observation must be an object");
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!RESPONSE_FIELDS.contains(fields.next())) {
                throw new IllegalArgumentException("trace response contains an unknown field");
            }
        }
        JsonNode duration = node.get("durationMs");
        if (duration == null || !duration.isIntegralNumber()) {
            throw new IllegalArgumentException("trace duration is invalid");
        }
        String errorCode = text(node, "errorCode", false);
        return new TraceObservation(
                text(node, "traceId", true),
                text(node, "spanId", true),
                text(node, "skillId", true),
                text(node, "version", true),
                text(node, "operation", true),
                text(node, "status", true),
                duration.longValue(),
                errorCode == null ? "" : errorCode,
                text(node, "dataSource", true),
                OffsetDateTime.parse(text(node, "occurredAt", true)),
                text(node, "runtimeId", false),
                text(node, "mcpServerId", false),
                text(node, "llmProviderId", false));
    }

    private String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException("trace field is missing");
            return null;
        }
        if (!value.isTextual()) throw new IllegalArgumentException("trace field is not text");
        return value.textValue();
    }

    private boolean credentialAvailable() {
        try {
            credentials.resolve(config.credentialRef());
            return true;
        } catch (ProviderUnavailableException exception) {
            return false;
        }
    }

    private void ensureSuccess(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return;
        String code = statusCode == 429 ? "RATE_LIMITED"
                : statusCode >= 500 ? "TEMPORARY_UNAVAILABLE" : "UPSTREAM_REJECTED";
        throw new ProviderUnavailableException(ID, code);
    }
}
