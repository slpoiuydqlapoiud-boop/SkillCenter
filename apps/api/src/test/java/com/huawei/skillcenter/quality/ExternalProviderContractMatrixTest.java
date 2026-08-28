package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalProviderContractMatrixTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluationAdapterSendsCaseIdButNeverBusinessDefinedCaseName() {
        AtomicReference<ProviderHttpRequest> captured = new AtomicReference<>();
        DeepEvalEvaluationAdapter adapter = new DeepEvalEvaluationAdapter(
                new ProviderAdapterConfig(true, "https://deepeval.internal/v1", "secret://deepeval", "http"),
                request -> {
                    captured.set(request);
                    return new ProviderHttpResponse(200, "{\"passed\":true,\"score\":100,\"reason\":\"ok\"}");
                },
                reference -> "secret-value",
                mapper);

        adapter.evaluate(new EvaluationCase("case-sensitive", "Customer contract contents"),
                new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, "openclaw-runner", "contract-v1",
                        "production", 20, "sha256:abc", ""));

        assertThat(captured).hasValueSatisfying(request -> assertThat(request.body())
                .contains("\"caseId\":\"case-sensitive\"")
                .doesNotContain("Customer contract contents")
                .doesNotContain("caseName"));
    }

    @Test
    void evaluationAdapterRejectsUnknownResponseFields() {
        DeepEvalEvaluationAdapter adapter = new DeepEvalEvaluationAdapter(
                new ProviderAdapterConfig(true, "https://deepeval.internal/v1", "secret://deepeval", "http"),
                request -> new ProviderHttpResponse(200,
                        "{\"passed\":true,\"score\":100,\"reason\":\"ok\",\"prompt\":\"private\"}"),
                reference -> "secret-value",
                mapper);

        assertThatThrownBy(() -> adapter.evaluate(new EvaluationCase("case-a", "Synthetic"),
                new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, "openclaw-runner", "contract-v1",
                        "production", 20, "sha256:abc", "")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_INVALID_RESPONSE: deepeval-evaluation")
                .hasMessageNotContaining("private");
    }

    @Test
    void runnerAdapterRejectsUnknownResponseFields() {
        OpenClawRunnerAdapter adapter = new OpenClawRunnerAdapter(
                new ProviderAdapterConfig(true, "https://runner.internal/v1", "secret://runner", "http"),
                request -> new ProviderHttpResponse(200,
                        "{\"status\":\"SUCCEEDED\",\"providerVersion\":\"runner-v1\","
                                + "\"durationMs\":20,\"outputHash\":\"sha256:abc\",\"errorCode\":\"\","
                                + "\"output\":\"private\"}"),
                reference -> "secret-value",
                mapper);

        assertThatThrownBy(() -> adapter.execute(new RunnerExecutionRequest(
                "skill-a", "1.0.0", "run-a", "suite-a", "case-a", 1_000, "success")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_INVALID_RESPONSE: openclaw-runner")
                .hasMessageNotContaining("private");
    }

    @Test
    void observabilityAdapterRejectsUnknownAcknowledgementFields() {
        LangfuseObservabilityAdapter adapter = new LangfuseObservabilityAdapter(
                new ProviderAdapterConfig(true, "https://langfuse.internal/v1", "secret://langfuse", "http"),
                request -> new ProviderHttpResponse(202,
                        "{\"accepted\":true,\"input\":\"private\"}"),
                reference -> "secret-value",
                mapper);

        assertThatThrownBy(() -> adapter.record(new RunnerExecutionSummary(
                "run-a", "skill-a", "1.0.0", RunnerExecutionStatus.SUCCEEDED, 20, "", "production",
                Instant.parse("2026-08-25T00:00:00Z"), "openclaw", "mcp-a", "llm-a")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_INVALID_RESPONSE: langfuse-observability")
                .hasMessageNotContaining("private");
    }

    @Test
    void providerResponseRejectsOversizedBodiesBeforeAdapterParsing() {
        assertThatThrownBy(() -> new ProviderHttpResponse(200, "x".repeat(256_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("body must not exceed 256000 characters");
    }
}
