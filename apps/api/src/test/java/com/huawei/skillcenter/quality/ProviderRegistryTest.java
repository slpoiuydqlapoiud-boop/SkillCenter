package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRegistryTest {
    @Test
    void exposesVersionedMockProviderCapabilities() {
        ProviderRegistry registry = new ProviderRegistry(new MockRunner(), new MockEvaluationProvider(), new MockObservabilityProvider());

        assertThat(registry.list()).extracting(ProviderDescriptor::id)
                .containsExactly("mock-runner", "mock-evaluation", "mock-observability");
        assertThat(registry.list()).allSatisfy(provider -> {
            assertThat(provider.version()).isEqualTo("1.0");
            assertThat(provider.status()).isEqualTo("UP");
        });
    }

    @Test
    void exposesOptionalCapabilitiesAndHealthReasonWithoutChangingExistingProviderIds() {
        ProviderRegistry registry = new ProviderRegistry(
                new MockRunner(), new MockEvaluationProvider(), new MockObservabilityProvider());

        assertThat(registry.list().get(0).capabilities()).containsExactly("execute", "timeout", "cancel");
        assertThat(registry.list().get(0).healthReason()).isBlank();
        assertThat(registry.list().get(1).capabilities()).containsExactly("evaluate", "score");
        assertThat(registry.list().get(2).capabilities()).containsExactly("summary", "metrics");
    }
}
