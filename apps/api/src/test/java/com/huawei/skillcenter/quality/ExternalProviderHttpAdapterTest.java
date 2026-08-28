package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalProviderHttpAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderAdapterConfig config = new ProviderAdapterConfig(
            true, "https://provider.internal/v1", "secret://provider", "http");

    @Test
    void deepEvalMapsSafeRunnerMetadataAndStrictEvaluationResult() {
        AtomicReference<ProviderHttpRequest> captured = new AtomicReference<>();
        DeepEvalEvaluationAdapter adapter = new DeepEvalEvaluationAdapter(config, request -> {
            captured.set(request);
            return new ProviderHttpResponse(200, "{\"passed\":true,\"score\":87,\"reason\":\"stable\"}");
        }, reference -> "secret-value", mapper);

        EvaluationResult result = adapter.evaluate(new EvaluationCase("case-a", "Synthetic check"),
                new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, "openclaw-runner", "runner-2",
                        "production", 42, "sha256:abc", ""));

        assertThat(adapter.health().status()).isEqualTo("UP");
        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(87);
        assertThat(result.reason()).isEqualTo("stable");
        assertThat(captured).hasValueSatisfying(request -> assertThat(request.body())
                .contains("\"caseId\":\"case-a\"")
                .contains("\"providerVersion\":\"runner-2\"")
                .doesNotContain("caseName", "Synthetic check", "prompt", "input", "secret-value"));
    }

    @Test
    void langfuseSendsOnlySummaryMetadataAndAcceptsStatusAcknowledgement() {
        AtomicReference<ProviderHttpRequest> captured = new AtomicReference<>();
        LangfuseObservabilityAdapter adapter = new LangfuseObservabilityAdapter(config, request -> {
            captured.set(request);
            return new ProviderHttpResponse(202, "{\"accepted\":true}");
        }, reference -> "secret-value", mapper);

        adapter.record(new RunnerExecutionSummary("run-a", "skill-a", "1.2.3",
                RunnerExecutionStatus.FAILED, 55, "UPSTREAM_TIMEOUT", "production",
                Instant.parse("2026-08-25T00:00:00Z"), "openclaw", "mcp-a", "llm-a"));

        assertThat(captured).hasValueSatisfying(request -> assertThat(request.body())
                .contains("\"runId\":\"run-a\"")
                .contains("\"runtimeId\":\"openclaw\"")
                .contains("\"errorCode\":\"UPSTREAM_TIMEOUT\"")
                .doesNotContain("prompt", "input", "tool", "secret-value"));
    }

    @Test
    void adaptersMapNonSuccessResponsesAndRejectInvalidScoresWithoutBodyLeak() {
        DeepEvalEvaluationAdapter rateLimited = new DeepEvalEvaluationAdapter(config,
                request -> new ProviderHttpResponse(429, "private provider body"),
                reference -> "secret-value", mapper);
        DeepEvalEvaluationAdapter invalidScore = new DeepEvalEvaluationAdapter(config,
                request -> new ProviderHttpResponse(200, "{\"passed\":true,\"score\":101,\"reason\":\"private\"}"),
                reference -> "secret-value", mapper);

        assertThatThrownBy(() -> rateLimited.evaluate(new EvaluationCase("case-a", "Synthetic"),
                new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, "runner", "1", "production", 1, "", "")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("RATE_LIMITED: deepeval-evaluation")
                .hasMessageNotContaining("private provider body");
        assertThatThrownBy(() -> invalidScore.evaluate(new EvaluationCase("case-a", "Synthetic"),
                new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, "runner", "1", "production", 1, "", "")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("UPSTREAM_INVALID_RESPONSE: deepeval-evaluation");
    }
}
