package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.ProviderAdapterConfig;
import com.huawei.skillcenter.quality.ProviderHttpRequest;
import com.huawei.skillcenter.quality.ProviderHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseTraceProviderAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderAdapterConfig config = new ProviderAdapterConfig(
            true, "https://langfuse.internal/v1/traces", "secret://langfuse", "http");

    @Test
    void queriesSafeTraceMetadataAndMapsExternalObservations() {
        AtomicReference<ProviderHttpRequest> captured = new AtomicReference<>();
        LangfuseTraceProviderAdapter adapter = new LangfuseTraceProviderAdapter(config, request -> {
            captured.set(request);
            return new ProviderHttpResponse(200, "[{\"traceId\":\"trace-a\",\"spanId\":\"span-a\","
                    + "\"skillId\":\"skill-a\",\"version\":\"1.0.0\",\"operation\":\"skill.run\","
                    + "\"status\":\"failure\",\"durationMs\":120,\"errorCode\":\"UPSTREAM_TIMEOUT\","
                    + "\"dataSource\":\"production\",\"occurredAt\":\"2026-08-25T00:00:00Z\","
                    + "\"runtimeId\":\"openclaw\",\"mcpServerId\":\"mcp-a\",\"llmProviderId\":\"llm-a\"}]");
        }, reference -> "secret-value", mapper);

        TraceQuery query = new TraceQuery(RuntimeOperationsWindow.SIXTY_MINUTES, "skill-a", "1.0.0",
                null, "failure", "production", "openclaw", "mcp-a", "llm-a");
        var result = adapter.query(query);

        assertThat(adapter.health().status()).isEqualTo("UP");
        assertThat(result).singleElement().satisfies(trace -> {
            assertThat(trace.traceId()).isEqualTo("trace-a");
            assertThat(trace.spanId()).isEqualTo("span-a");
            assertThat(trace.status()).isEqualTo("failure");
            assertThat(trace.errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
            assertThat(trace.dataSource()).isEqualTo("production");
            assertThat(trace.runtimeId()).isEqualTo("openclaw");
        });
        assertThat(captured).hasValueSatisfying(request -> assertThat(request.body())
                .contains("\"windowSeconds\":3600")
                .contains("\"skillId\":\"skill-a\"")
                .contains("\"dataSource\":\"production\"")
                .contains("\"runtimeId\":\"openclaw\"")
                .doesNotContain("prompt", "input", "output", "tool", "secret-value"));
    }

    @Test
    void rejectsUnknownOrSensitiveTraceFieldsAsStableProviderError() {
        LangfuseTraceProviderAdapter adapter = new LangfuseTraceProviderAdapter(config,
                request -> new ProviderHttpResponse(200,
                        "[{\"traceId\":\"trace-a\",\"spanId\":\"span-a\","
                                + "\"skillId\":\"skill-a\",\"version\":\"1.0.0\","
                                + "\"operation\":\"skill.run\",\"status\":\"failure\","
                                + "\"durationMs\":10,\"errorCode\":\"E\",\"dataSource\":\"production\","
                                + "\"occurredAt\":\"2026-08-25T00:00:00Z\",\"prompt\":\"do-not-leak\"}]"),
                reference -> "secret-value", mapper);

        assertThatThrownBy(() -> adapter.query(new TraceQuery(RuntimeOperationsWindow.FIVE_MINUTES,
                null, null, null, null, "production")))
                .isInstanceOf(com.huawei.skillcenter.quality.ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_INVALID_RESPONSE: langfuse-observability")
                .hasMessageNotContaining("do-not-leak");
    }

    @Test
    void missingSecretIsNotReadyAndNeverFallsBackToMock() {
        LangfuseTraceProviderAdapter adapter = new LangfuseTraceProviderAdapter(config,
                request -> new ProviderHttpResponse(200, "[]"),
                reference -> {
                    throw new com.huawei.skillcenter.quality.ProviderUnavailableException(
                            "provider-credential", "EXTERNAL_ADAPTER_NOT_CONFIGURED");
                }, mapper);

        assertThat(adapter.health().status()).isEqualTo("NOT_CONFIGURED");
        assertThatThrownBy(() -> adapter.query(new TraceQuery(RuntimeOperationsWindow.FIVE_MINUTES,
                null, null, null, null, "production")))
                .isInstanceOf(com.huawei.skillcenter.quality.ProviderUnavailableException.class)
                .hasMessage("EXTERNAL_ADAPTER_NOT_CONFIGURED: langfuse-observability");
    }
}
