package com.huawei.skillcenter.analytics;

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
class AnalyticsControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void analyticsOverviewContainsSevenDaySeries() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpis.calls").isNumber())
                .andExpect(jsonPath("$.data.series").isArray())
                .andExpect(jsonPath("$.data.series.length()").value(7))
                .andExpect(jsonPath("$.data.topSkills").isArray());
    }

    @Test
    void analyticsOverviewAcceptsThirtyDayRangeAndFilters() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview")
                        .param("range", "30d")
                        .param("skillId", "eox-query")
                        .header("X-User-Id", "admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series.length()").value(30))
                .andExpect(jsonPath("$.data.kpis.activeUsers").isNumber())
                .andExpect(jsonPath("$.data.latency.p95Ms").isNumber());
    }

    @Test
    void analyticsOverviewRejectsInvalidCustomRangeWithInvalidRequest() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview")
                        .param("range", "custom")
                        .param("from", "2026-01-01")
                        .param("to", "2026-04-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
