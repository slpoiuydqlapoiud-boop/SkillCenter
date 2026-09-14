package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProviderConnectivityProbeControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanProbeConfiguredProviderTargetsWithoutExposingConfiguration() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quality/provider-readiness/probe")
                        .header("X-User-Id", "quality-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].providerId").value("deepeval-evaluation"))
                .andExpect(jsonPath("$.data[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.data[0].reason").value("MOCK_PROVIDER_ACTIVE"))
                .andExpect(jsonPath("$.data[0].endpoint").doesNotExist())
                .andExpect(jsonPath("$.data[0].credentialRef").doesNotExist());
    }

    @Test
    void nonAdminCannotProbeProviderConnectivity() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quality/provider-readiness/probe")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanProbeOneProviderById() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quality/provider-readiness/probe")
                        .queryParam("providerId", "openclaw-runner")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].providerId").value("openclaw-runner"));
    }

    @Test
    void adminCanReadLatestSafeProviderProbeResults() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quality/provider-readiness/probe")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/admin/quality/provider-readiness/probe/latest")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].providerId").value("deepeval-evaluation"))
                .andExpect(jsonPath("$.data[0].endpoint").doesNotExist())
                .andExpect(jsonPath("$.data[0].credentialRef").doesNotExist());
    }
}
