package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local-only adapter budget. It measures contract serialization/parsing, not upstream latency.
 * The test intentionally uses an in-memory transport and therefore never proves production SLA.
 */
class ExternalProviderContractPerformanceTest {
    private static final int ITERATIONS = 300;
    private static final long MAX_NANOS = 2_000_000_000L;

    @Test
    void localProviderAdaptersStayWithinTheContractSerializationBudget() {
        ProviderAdapterConfig config = new ProviderAdapterConfig(
                true, "https://provider.internal/v1", "secret://provider", "http");
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();

        OpenClawRunnerAdapter runner = new OpenClawRunnerAdapter(config, request -> {
            calls.incrementAndGet();
            return new ProviderHttpResponse(200,
                    "{\"status\":\"SUCCEEDED\",\"providerVersion\":\"runner-v1\","
                            + "\"durationMs\":20,\"outputHash\":\"sha256:abc\",\"errorCode\":\"\"}");
        }, reference -> "secret-value", mapper);
        DeepEvalEvaluationAdapter evaluation = new DeepEvalEvaluationAdapter(config, request -> {
            calls.incrementAndGet();
            return new ProviderHttpResponse(200, "{\"passed\":true,\"score\":100,\"reason\":\"ok\"}");
        }, reference -> "secret-value", mapper);
        LangfuseObservabilityAdapter observability = new LangfuseObservabilityAdapter(config, request -> {
            calls.incrementAndGet();
            return new ProviderHttpResponse(202, "{\"accepted\":true}");
        }, reference -> "secret-value", mapper);

        RunnerExecutionRequest runnerRequest = new RunnerExecutionRequest(
                "skill-a", "1.0.0", "run-a", "suite-a", "case-a", 1_000, "success",
                "runtime-a", "mcp-a", "llm-a");
        EvaluationCase evaluationCase = new EvaluationCase("case-a", "Synthetic case");
        RunnerExecutionResult executionResult = new RunnerExecutionResult(
                RunnerExecutionStatus.SUCCEEDED, "openclaw-runner", "runner-v1", "production",
                20, "sha256:abc", "");
        RunnerExecutionSummary summary = new RunnerExecutionSummary(
                "run-a", "skill-a", "1.0.0", RunnerExecutionStatus.SUCCEEDED, 20, "", "production",
                Instant.parse("2026-08-25T00:00:00Z"), "runtime-a", "mcp-a", "llm-a");

        long started = System.nanoTime();
        for (int index = 0; index < ITERATIONS; index++) {
            runner.execute(runnerRequest);
            evaluation.evaluate(evaluationCase, executionResult);
            observability.record(summary);
        }
        long elapsed = System.nanoTime() - started;

        assertThat(calls).hasValue(ITERATIONS * 3);
        assertThat(elapsed)
                .as("local provider contract serialization/parsing budget")
                .isLessThan(MAX_NANOS);
    }
}
