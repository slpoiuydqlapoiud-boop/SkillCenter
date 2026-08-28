package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.LangfuseTraceProviderAdapter;
import com.huawei.skillcenter.operations.TraceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "skill-center.providers.runner=openclaw",
        "skill-center.providers.evaluation=deepeval",
        "skill-center.providers.observability=langfuse",
        "skill-center.providers.trace=langfuse",
        "skill-center.providers.openclaw.endpoint=http://openclaw.internal/v1/execute",
        "skill-center.providers.openclaw.credential-ref=secret://env/MISSING_OPENCLAW",
        "skill-center.providers.openclaw.mode=http",
        "skill-center.providers.deepeval.endpoint=http://deepeval.internal/v1/evaluate",
        "skill-center.providers.deepeval.credential-ref=secret://env/MISSING_DEEPEVAL",
        "skill-center.providers.deepeval.mode=http",
        "skill-center.providers.langfuse.endpoint=http://langfuse.internal/v1/observe",
        "skill-center.providers.langfuse.trace-endpoint=http://langfuse.internal/v1/traces",
        "skill-center.providers.langfuse.credential-ref=secret://env/MISSING_LANGFUSE",
        "skill-center.providers.langfuse.mode=http"
})
class ProviderHttpModeSelectionTest {
    @Autowired
    private SkillRunner runner;

    @Autowired
    private EvaluationProvider evaluator;

    @Autowired
    private ObservabilityProvider observability;

    @Autowired
    private TraceProvider traceProvider;

    @Test
    void selectsHttpAdaptersButReportsMissingSecretWithoutFallingBackToMock() {
        assertThat(runner).isInstanceOf(OpenClawRunnerAdapter.class);
        assertThat(evaluator).isInstanceOf(DeepEvalEvaluationAdapter.class);
        assertThat(observability).isInstanceOf(LangfuseObservabilityAdapter.class);
        assertThat(traceProvider).isInstanceOf(LangfuseTraceProviderAdapter.class);
        assertThat(runner.health().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(evaluator.health().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(observability.health().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(((LangfuseTraceProviderAdapter) traceProvider).health().status()).isEqualTo("NOT_CONFIGURED");
    }
}
