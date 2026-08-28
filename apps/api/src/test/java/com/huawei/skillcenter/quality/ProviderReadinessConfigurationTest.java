package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "skill-center.providers.runner=openclaw")
@AutoConfigureMockMvc
class ProviderReadinessConfigurationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void readinessListsActiveProvidersMissingConfiguration() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/provider-readiness")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.notConfiguredProviderCount").value(1))
                .andExpect(jsonPath("$.data.notConfiguredProviderIds[0]").value("openclaw-runner"))
                .andExpect(jsonPath("$.data.reason").value("ACTIVE_PROVIDER_NOT_CONFIGURED"));
    }
}
