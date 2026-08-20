package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest(properties = {
        "skill-center.security.rate-limit.export-create=1",
        "skill-center.operations.metrics-token=smoke-test-token",
        "skill-center.operations.metrics-storage=memory"
})
@AutoConfigureMockMvc
class OperationsObservabilitySmokeTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void metricsReflectNormalAndSecurityBoundaryRequestsWithoutSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/events/invocations")
                        .header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/exports")
                        .header("X-User-Id", "observability-rate-user")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/exports")
                        .header("X-User-Id", "observability-rate-user")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/v1/admin/operations/metrics")
                        .header("X-User-Id", "observability-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requests.total").isNumber())
                .andExpect(jsonPath("$.data.securityEvents.CSRF_ORIGIN_REJECTED").value(1))
                .andExpect(jsonPath("$.data.securityEvents.RATE_LIMITED").value(1))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.requestBody").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void protectedPrometheusAndAlertContractsAreAvailableWithoutSensitiveFields() throws Exception {
        mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/metrics")
                        .header("X-Metrics-Token", "smoke-test-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("skillcenter_requests_total")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("smoke-test-token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("userId"))));
        mockMvc.perform(get("/api/v1/admin/operations/alerts")
                        .header("X-User-Id", "observability-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].status").isString());
    }
}
