package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenClaw contract adapter with an explicitly opt-in, metadata-only HTTP mode. */
public final class OpenClawRunnerAdapter implements SkillRunner {
    private static final String ID = "openclaw-runner";
    private final ProviderAdapterConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ObjectMapper mapper;

    public OpenClawRunnerAdapter() {
        this(ProviderAdapterConfig.disabled(), new JavaHttpProviderTransport(),
                new EnvironmentProviderCredentialResolver(), new ObjectMapper());
    }

    public OpenClawRunnerAdapter(ProviderAdapterConfig config) {
        this(config, new JavaHttpProviderTransport(), new EnvironmentProviderCredentialResolver(), new ObjectMapper());
    }

    OpenClawRunnerAdapter(ProviderAdapterConfig config, ProviderHttpTransport transport,
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
    public String dataSource() {
        return "production";
    }

    @Override
    public List<String> capabilities() {
        return List.of("execute", "timeout", "cancel");
    }

    @Override
    public ProviderHealth health() {
        if (!config.configured()) return ProviderHealth.notConfigured();
        if (!config.httpEnabled()) return ProviderHealth.contractOnly();
        return credentialAvailable() ? ProviderHealth.up() : ProviderHealth.notConfigured();
    }

    @Override
    public RunnerExecutionResult execute(RunnerExecutionRequest request) {
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
            String body = mapper.writeValueAsString(requestBody(request));
            ProviderHttpResponse response = transport.post(new ProviderHttpRequest(ID,
                    java.net.URI.create(config.endpoint()), credential, body,
                    Duration.ofMillis(request.timeoutMs())));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderUnavailableException(ID,
                        response.statusCode() == 429 ? "RATE_LIMITED" : "UPSTREAM_REJECTED");
            }
            WireResponse result = mapper.readValue(response.body(), WireResponse.class);
            validate(result);
            return new RunnerExecutionResult(result.status(), ID, result.providerVersion(), "production",
                    result.durationMs(), result.outputHash(), result.errorCode());
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ProviderUnavailableException(ID, "UPSTREAM_INVALID_RESPONSE");
        }
    }

    private Map<String, Object> requestBody(RunnerExecutionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", request.skillId());
        body.put("skillVersion", request.skillVersion());
        body.put("evaluationRunId", request.evaluationRunId());
        body.put("suiteId", request.suiteId());
        body.put("caseId", request.caseId());
        body.put("timeoutMs", request.timeoutMs());
        body.put("scenario", request.scenario());
        body.put("runtimeId", request.runtimeId());
        body.put("mcpServerId", request.mcpServerId());
        body.put("llmProviderId", request.llmProviderId());
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

    private void validate(WireResponse result) {
        if (result == null || result.status() == null || result.providerVersion() == null
                || result.providerVersion().isBlank() || result.providerVersion().length() > 128
                || result.durationMs() < 0 || result.durationMs() > 120_000
                || result.outputHash() == null || result.outputHash().length() > 256
                || result.errorCode() == null || result.errorCode().length() > 128) {
            throw new IllegalArgumentException("invalid provider response");
        }
    }

    private record WireResponse(RunnerExecutionStatus status, String providerVersion,
                                long durationMs, String outputHash, String errorCode) {
    }
}
