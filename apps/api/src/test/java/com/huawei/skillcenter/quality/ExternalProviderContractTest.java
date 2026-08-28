package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalProviderContractTest {
    @Test
    void openClawRunnerIsContractOnlyAndFailsClosedWithoutCallingNetwork() {
        OpenClawRunnerAdapter adapter = new OpenClawRunnerAdapter(ProviderAdapterConfig.disabled());

        assertThat(adapter.providerId()).isEqualTo("openclaw-runner");
        assertThat(adapter.providerVersion()).isEqualTo("contract-v1");
        assertThat(adapter.capabilities()).containsExactly("execute", "timeout", "cancel");
        assertThat(adapter.health().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(adapter.health().reason()).isEqualTo("EXTERNAL_ADAPTER_NOT_CONFIGURED");
        assertThatThrownBy(() -> adapter.execute(new RunnerExecutionRequest(
                "skill-a", "1.0.0", "run-a", "smoke", "case-1", 1000, "success")))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(error -> {
                    ProviderUnavailableException exception = (ProviderUnavailableException) error;
                    assertThat(exception.providerId()).isEqualTo("openclaw-runner");
                    assertThat(exception.code()).isEqualTo("EXTERNAL_ADAPTER_NOT_CONFIGURED");
                });
    }

    @Test
    void deepEvalAndLangfuseAdaptersExposeStableContractsAndNeverLeakBusinessContent() {
        DeepEvalEvaluationAdapter evaluator = new DeepEvalEvaluationAdapter(ProviderAdapterConfig.disabled());
        LangfuseObservabilityAdapter observability = new LangfuseObservabilityAdapter(ProviderAdapterConfig.disabled());
        EvaluationCase evaluationCase = new EvaluationCase("case-secret", "customer prompt: do-not-store");

        assertThat(evaluator.providerId()).isEqualTo("deepeval-evaluation");
        assertThat(evaluator.providerVersion()).isEqualTo("contract-v1");
        assertThat(evaluator.capabilities()).containsExactly("evaluate", "score", "compare");
        assertThat(observability.providerId()).isEqualTo("langfuse-observability");
        assertThat(observability.providerVersion()).isEqualTo("contract-v1");
        assertThat(observability.capabilities()).containsExactly("trace-reference", "metrics", "errors");
        assertThatThrownBy(() -> evaluator.evaluate(evaluationCase,
                new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, "mock-runner", "1.0", "mock", 10, "hash", "")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageNotContaining("customer prompt")
                .satisfies(error -> assertThat(((ProviderUnavailableException) error).code())
                        .isEqualTo("EXTERNAL_ADAPTER_NOT_CONFIGURED"));
        assertThatThrownBy(() -> observability.record(new RunnerExecutionSummary(
                "run-a", "skill-a", "1.0.0", RunnerExecutionStatus.SUCCEEDED, 10, "", "mock")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageNotContaining("customer prompt");
    }

    @Test
    void adapterConfigurationAcceptsReferencesButRejectsInlineSecrets() {
        ProviderAdapterConfig safe = new ProviderAdapterConfig(true,
                "https://runner.internal", "secret://skillcenter/openclaw");

        assertThat(safe.configured()).isTrue();
        assertThat(safe.credentialRef()).isEqualTo("secret://skillcenter/openclaw");
        assertThatThrownBy(() -> new ProviderAdapterConfig(true,
                "https://runner.internal", "Bearer abc123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credentialRef must reference a secret, not contain a raw credential");
    }

    @Test
    void adapterConfigurationRejectsUnsafeEndpointsBeforeAnyAdapterCanUseThem() {
        assertThatThrownBy(() -> new ProviderAdapterConfig(true,
                "file:///etc/passwd", "secret://skillcenter/provider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint must be an HTTP(S) URL without credentials, query, or fragment");
        assertThatThrownBy(() -> new ProviderAdapterConfig(true,
                "https://user:password@runner.internal", "secret://skillcenter/provider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint must be an HTTP(S) URL without credentials, query, or fragment");
        assertThatThrownBy(() -> new ProviderAdapterConfig(true,
                "https://runner.internal?token=inline", "secret://skillcenter/provider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint must be an HTTP(S) URL without credentials, query, or fragment");
    }

    @Test
    void externalProviderCatalogListsContractOnlyAdaptersWithoutEnablingThem() {
        List<ExternalProviderDescriptor> descriptors = ExternalProviderCatalog.list();

        assertThat(descriptors).extracting(ExternalProviderDescriptor::id)
                .containsExactly("openclaw-runner", "deepeval-evaluation", "langfuse-observability");
        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.version()).isEqualTo("contract-v1");
            assertThat(descriptor.status()).isEqualTo("CONTRACT_ONLY");
            assertThat(descriptor.configReference()).isEqualTo("secret://<provider>");
        });
    }

    @Test
    void configuredContractAdaptersFailClosedWithNotEnabledInsteadOfNotConfigured() {
        ProviderAdapterConfig configured = new ProviderAdapterConfig(true,
                "https://provider.internal", "secret://skillcenter/provider");
        OpenClawRunnerAdapter runner = new OpenClawRunnerAdapter(configured);
        DeepEvalEvaluationAdapter evaluator = new DeepEvalEvaluationAdapter(configured);
        LangfuseObservabilityAdapter observability = new LangfuseObservabilityAdapter(configured);

        assertThat(runner.health().status()).isEqualTo("CONTRACT_ONLY");
        assertThatThrownBy(() -> runner.execute(new RunnerExecutionRequest(
                "skill-a", "1.0.0", "run-a", "smoke", "case-1", 1000, "success")))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(error -> assertThat(((ProviderUnavailableException) error).code())
                        .isEqualTo("EXTERNAL_ADAPTER_NOT_ENABLED"));
        assertThatThrownBy(() -> evaluator.evaluate(new EvaluationCase("case-1", "safe"),
                new RunnerExecutionResult(RunnerExecutionStatus.SUCCEEDED, "mock", "1.0", "mock", 10, "", "")))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(error -> assertThat(((ProviderUnavailableException) error).code())
                        .isEqualTo("EXTERNAL_ADAPTER_NOT_ENABLED"));
        assertThatThrownBy(() -> observability.record(new RunnerExecutionSummary(
                "run-a", "skill-a", "1.0.0", RunnerExecutionStatus.SUCCEEDED, 10, "", "mock")))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(error -> assertThat(((ProviderUnavailableException) error).code())
                        .isEqualTo("EXTERNAL_ADAPTER_NOT_ENABLED"));
    }
}
