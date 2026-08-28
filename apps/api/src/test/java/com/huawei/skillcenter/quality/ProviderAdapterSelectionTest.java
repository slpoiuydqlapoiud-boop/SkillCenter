package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "skill-center.providers.runner=openclaw",
        "skill-center.providers.evaluation=deepeval",
        "skill-center.providers.observability=langfuse",
        "skill-center.providers.openclaw.endpoint=http://openclaw.internal",
        "skill-center.providers.openclaw.credential-ref=secret://openclaw",
        "skill-center.providers.deepeval.endpoint=http://deepeval.internal",
        "skill-center.providers.deepeval.credential-ref=secret://deepeval",
        "skill-center.providers.langfuse.endpoint=http://langfuse.internal",
        "skill-center.providers.langfuse.credential-ref=secret://langfuse"
})
@AutoConfigureMockMvc
class ProviderAdapterSelectionTest {
    @Autowired
    private ProviderRegistry providerRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void configuredProviderKindsSelectContractAdaptersWithoutNetworkCalls() {
        assertThat(providerRegistry.list()).extracting(ProviderDescriptor::id)
                .containsExactly("openclaw-runner", "deepeval-evaluation", "langfuse-observability");
        assertThat(providerRegistry.list()).allSatisfy(provider ->
                assertThat(provider.status()).isEqualTo("CONTRACT_ONLY"));
    }

    @Test
    void readinessRemainsPartialWhenSelectedAdaptersAreContractOnly() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/admin/quality/provider-readiness")
                        .header("X-User-Role", "admin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.contractOnlyProviderCount").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.reason").value("EXTERNAL_PROVIDERS_CONTRACT_ONLY"));
    }
}
