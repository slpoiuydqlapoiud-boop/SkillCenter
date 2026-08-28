package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DeepEval port placeholder. The platform owns evaluation orchestration; no DeepEval SDK is loaded here. */
public final class DeepEvalEvaluationAdapter implements EvaluationProvider {
    private static final String ID = "deepeval-evaluation";
    private final ProviderAdapterConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ObjectMapper mapper;

    public DeepEvalEvaluationAdapter() {
        this(ProviderAdapterConfig.disabled());
    }

    public DeepEvalEvaluationAdapter(ProviderAdapterConfig config) {
        this.config = config == null ? ProviderAdapterConfig.disabled() : config;
        this.transport = new JavaHttpProviderTransport();
        this.credentials = new EnvironmentProviderCredentialResolver();
        this.mapper = new ObjectMapper();
    }

    DeepEvalEvaluationAdapter(ProviderAdapterConfig config, ProviderHttpTransport transport,
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
        return List.of("evaluate", "score", "compare");
    }

    @Override
    public ProviderHealth health() {
        if (!config.configured()) return ProviderHealth.notConfigured();
        if (!config.httpEnabled()) return ProviderHealth.contractOnly();
        return credentialAvailable() ? ProviderHealth.up() : ProviderHealth.notConfigured();
    }

    @Override
    public EvaluationResult evaluate(EvaluationCase evaluationCase, RunnerExecutionResult executionResult) {
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
            String body = mapper.writeValueAsString(requestBody(evaluationCase, executionResult));
            ProviderHttpResponse response = transport.post(new ProviderHttpRequest(ID, URI.create(config.endpoint()),
                    credential, body, Duration.ofSeconds(30)));
            ensureSuccess(response.statusCode());
            WireResponse result = mapper.readValue(response.body(), WireResponse.class);
            validate(result);
            return new EvaluationResult(result.passed(), result.score(), result.reason());
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ProviderUnavailableException(ID, "UPSTREAM_INVALID_RESPONSE");
        }
    }

    private Map<String, Object> requestBody(EvaluationCase evaluationCase, RunnerExecutionResult executionResult) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", evaluationCase.id());
        body.put("status", executionResult.status());
        body.put("providerId", executionResult.providerId());
        body.put("providerVersion", executionResult.providerVersion());
        body.put("dataSource", executionResult.dataSource());
        body.put("durationMs", executionResult.durationMs());
        body.put("outputHash", executionResult.outputHash());
        body.put("errorCode", executionResult.errorCode());
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
        if (result == null || result.passed() == null || result.score() == null
                || result.score() < 0 || result.score() > 100
                || result.reason() == null || result.reason().length() > 512
                || containsControlCharacter(result.reason())) {
            throw new IllegalArgumentException("invalid evaluation response");
        }
    }

    private void ensureSuccess(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return;
        String code = statusCode == 429 ? "RATE_LIMITED"
                : statusCode >= 500 ? "TEMPORARY_UNAVAILABLE" : "UPSTREAM_REJECTED";
        throw new ProviderUnavailableException(ID, code);
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 0x20 && character != '\n' && character != '\r' && character != '\t');
    }

    private record WireResponse(Boolean passed, Integer score, String reason) {
    }
}
