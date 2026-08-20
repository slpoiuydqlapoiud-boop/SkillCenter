package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.governance.ActorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationsAlertControllerTest {
    private final OperationsMetricsService metrics = new OperationsMetricsService(
            Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC), "", false);
    private final OperationsAlertService alerts = new OperationsAlertService(
            Clock.fixed(Instant.parse("2026-08-18T06:00:10Z"), ZoneOffset.UTC), metrics, 1_000, 0.05, 10, 10);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OperationsAlertController(alerts, new ActorResolver()))
            .setControllerAdvice(new GlobalExceptionHandler(metrics))
            .build();

    @Test
    void adminCanReadAlertsAndViewerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/operations/alerts")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/operations/alerts")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void invalidWindowReturnsInvalidRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/operations/alerts")
                        .param("window", "1h")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
