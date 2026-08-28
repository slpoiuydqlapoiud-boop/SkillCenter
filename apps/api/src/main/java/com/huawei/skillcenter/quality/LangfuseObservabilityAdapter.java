package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Langfuse port placeholder. Only redacted summaries may cross this boundary in a future adapter. */
public final class LangfuseObservabilityAdapter implements ObservabilityProvider {
    private static final String ID = "langfuse-observability";
    private final ProviderAdapterConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ObjectMapper mapper;

    public LangfuseObservabilityAdapter() {
        this(ProviderAdapterConfig.disabled());
    }

    public LangfuseObservabilityAdapter(ProviderAdapterConfig config) {
        this.config = config == null ? ProviderAdapterConfig.disabled() : config;
        this.transport = new JavaHttpProviderTransport();
        this.credentials = new EnvironmentProviderCredentialResolver();
        this.mapper = new ObjectMapper();
    }

    LangfuseObservabilityAdapter(ProviderAdapterConfig config, ProviderHttpTransport transport,
                                 ProviderCredentialResolver credentials, ObjectMapper mapper) {
        this.config = config == null ? ProviderAdapterConfig.disabled() : config;
        this.transport = transport == null ? new JavaHttpProviderTransport() : transport;
        this.credentials = credentials == null ? new EnvironmentProviderCredentialResolver() : credentials;
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
        return List.of("trace-reference", "metrics", "errors");
    }

    @Override
    public ProviderHealth health() {
        if (!config.configured()) return ProviderHealth.notConfigured();
        if (!config.httpEnabled()) return ProviderHealth.contractOnly();
        return credentialAvailable() ? ProviderHealth.up() : ProviderHealth.notConfigured();
    }

    @Override
    public void record(RunnerExecutionSummary summary) {
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
            String body = mapper.writeValueAsString(requestBody(summary));
            ProviderHttpResponse response = transport.post(new ProviderHttpRequest(ID, URI.create(config.endpoint()),
                    credential, body, Duration.ofSeconds(10)));
            ensureSuccess(response.statusCode());
            if (!response.body().isBlank()) {
                Acknowledgement acknowledgement = mapper.readValue(response.body(), Acknowledgement.class);
                if (acknowledgement.accepted() == null || !acknowledgement.accepted()) {
                    throw new IllegalArgumentException("observability acknowledgement was not accepted");
                }
            }
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ProviderUnavailableException(ID, "UPSTREAM_INVALID_RESPONSE");
        }
    }

    private Map<String, Object> requestBody(RunnerExecutionSummary summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", summary.runId());
        body.put("skillId", summary.skillId());
        body.put("skillVersion", summary.skillVersion());
        body.put("status", summary.status());
        body.put("durationMs", summary.durationMs());
        body.put("errorCode", summary.errorCode());
        body.put("dataSource", summary.dataSource());
        body.put("occurredAt", summary.occurredAt() == null ? "" : summary.occurredAt().toString());
        body.put("runtimeId", summary.runtimeId());
        body.put("mcpServerId", summary.mcpServerId());
        body.put("llmProviderId", summary.llmProviderId());
        return body;
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

    private record Acknowledgement(Boolean accepted) {
    }
}
