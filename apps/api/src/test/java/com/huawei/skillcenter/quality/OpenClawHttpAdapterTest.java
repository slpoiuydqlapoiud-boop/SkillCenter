package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenClawHttpAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void httpModeMapsOnlySafeRunnerMetadataAndExternalResult() throws Exception {
        AtomicReference<ProviderHttpRequest> captured = new AtomicReference<>();
        ProviderHttpTransport transport = request -> {
            captured.set(request);
            return new ProviderHttpResponse(200,
                    "{\"status\":\"SUCCEEDED\",\"providerVersion\":\"runner-2\","
                            + "\"durationMs\":42,\"outputHash\":\"sha256:abc\",\"errorCode\":\"\"}");
        };
        OpenClawRunnerAdapter adapter = new OpenClawRunnerAdapter(
                new ProviderAdapterConfig(true, "https://runner.internal/v1/execute", "secret://runner", "http"),
                transport, reference -> "secret-value", mapper);

        RunnerExecutionResult result = adapter.execute(new RunnerExecutionRequest(
                "skill-a", "1.2.3", "run-a", "suite-a", "case-a", 1000, "success",
                "openclaw", "mcp-a", "llm-a"));

        assertThat(adapter.health().status()).isEqualTo("UP");
        assertThat(result.status()).isEqualTo(RunnerExecutionStatus.SUCCEEDED);
        assertThat(result.providerId()).isEqualTo("openclaw-runner");
        assertThat(result.providerVersion()).isEqualTo("runner-2");
        assertThat(result.dataSource()).isEqualTo("production");
        assertThat(result.durationMs()).isEqualTo(42);
        assertThat(result.outputHash()).isEqualTo("sha256:abc");
        assertThat(captured).hasValueSatisfying(request -> {
            assertThat(request.endpoint()).isEqualTo(URI.create("https://runner.internal/v1/execute"));
            assertThat(request.bearerCredential()).isEqualTo("secret-value");
            assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(request.body()).contains("\"skillId\":\"skill-a\"")
                    .contains("\"runtimeId\":\"openclaw\"")
                    .contains("\"mcpServerId\":\"mcp-a\"")
                    .contains("\"llmProviderId\":\"llm-a\"")
                    .doesNotContain("prompt", "credential", "secret-value");
        });
    }

    @Test
    void invalidExternalResponseBecomesStableErrorWithoutBodyLeak() {
        OpenClawRunnerAdapter adapter = new OpenClawRunnerAdapter(
                new ProviderAdapterConfig(true, "https://runner.internal/v1/execute", "secret://runner", "http"),
                request -> new ProviderHttpResponse(200, "{\"status\":\"SUCCEEDED\",\"private\":\"do-not-leak\"}"),
                reference -> "secret-value", mapper);

        assertThatThrownBy(() -> adapter.execute(new RunnerExecutionRequest(
                "skill-a", "1.2.3", "run-a", "suite-a", "case-a", 1000, "success")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_INVALID_RESPONSE: openclaw-runner")
                .hasMessageNotContaining("do-not-leak");
    }

    @Test
    void contractModeRemainsFailClosedAndDoesNotCallTransport() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        OpenClawRunnerAdapter adapter = new OpenClawRunnerAdapter(
                new ProviderAdapterConfig(true, "https://runner.internal/v1/execute", "secret://runner"),
                request -> {
                    called.set(true);
                    return new ProviderHttpResponse(200, "{}");
                },
                reference -> "secret-value", mapper);

        assertThat(adapter.health().status()).isEqualTo("CONTRACT_ONLY");
        assertThatThrownBy(() -> adapter.execute(new RunnerExecutionRequest(
                "skill-a", "1.2.3", "run-a", "suite-a", "case-a", 1000, "success")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("EXTERNAL_ADAPTER_NOT_ENABLED: openclaw-runner");
        assertThat(called).hasValue(false);
    }
}
