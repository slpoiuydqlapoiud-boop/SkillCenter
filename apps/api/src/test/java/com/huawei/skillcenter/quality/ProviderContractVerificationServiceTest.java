package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderContractVerificationServiceTest {
    @Test
    void verifiesVersionAndCapabilitiesWithoutTreatingContractMatchAsExecutable() {
        ProviderRegistry registry = mock(ProviderRegistry.class);
        when(registry.list()).thenReturn(List.of(
                new ProviderDescriptor("openclaw-runner", "runner", "contract-v1", "CONTRACT_ONLY",
                        List.of("execute", "timeout", "cancel"), "EXTERNAL_ADAPTER_NOT_ENABLED"),
                new ProviderDescriptor("deepeval-evaluation", "evaluation", "contract-v0", "CONTRACT_ONLY",
                        List.of("evaluate"), "EXTERNAL_ADAPTER_NOT_ENABLED")
        ));

        List<ProviderContractVerification> result = new ProviderContractVerificationService(registry).verify();

        ProviderContractVerification openclaw = result.stream()
                .filter(item -> item.providerId().equals("openclaw-runner"))
                .findFirst().orElseThrow();
        assertThat(openclaw.verificationStatus()).isEqualTo("MATCHED");
        assertThat(openclaw.activeStatus()).isEqualTo("CONTRACT_ONLY");
        assertThat(openclaw.reason()).isEqualTo("PROVIDER_CONTRACT_MATCHED_NOT_ENABLED");

        ProviderContractVerification deepeval = result.stream()
                .filter(item -> item.providerId().equals("deepeval-evaluation"))
                .findFirst().orElseThrow();
        assertThat(deepeval.verificationStatus()).isEqualTo("MISMATCH");
        assertThat(deepeval.reason()).isEqualTo("PROVIDER_CONTRACT_MISMATCH");
        assertThat(deepeval.expectedVersion()).isEqualTo("contract-v1");
        assertThat(deepeval.actualVersion()).isEqualTo("contract-v0");
        assertThat(deepeval.missingCapabilities()).containsExactly("compare", "score");

        ProviderContractVerification langfuse = result.stream()
                .filter(item -> item.providerId().equals("langfuse-observability"))
                .findFirst().orElseThrow();
        assertThat(langfuse.verificationStatus()).isEqualTo("NOT_REGISTERED");
        assertThat(langfuse.reason()).isEqualTo("EXTERNAL_PROVIDER_NOT_REGISTERED");
        assertThat(langfuse.activeStatus()).isEqualTo("NOT_REGISTERED");
    }
}
