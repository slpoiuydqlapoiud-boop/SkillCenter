package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OperationsMetricsControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanReadAggregateOperationsMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/operations/metrics")
                        .param("window", "5m")
                        .header("X-User-Id", "metrics-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window").value("5m"))
                .andExpect(jsonPath("$.data.health.status").value("UP"))
                .andExpect(jsonPath("$.data.requests.total").isNumber())
                .andExpect(jsonPath("$.data.latency.p95Ms").isNumber())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.requestBody").doesNotExist())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void nonAdminCannotReadOperationsMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/admin/operations/metrics")
                        .header("X-User-Id", "metrics-viewer")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void invalidWindowReturnsInvalidRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/operations/metrics")
                        .param("window", "1h")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
