package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OptimizationWorkItemStaleness;
import com.huawei.skillcenter.operations.OptimizationWorkItemStalenessService;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OptimizationWorkItemHealthControllerTest {
    private MockMvc mockMvc;
    private ActorResolver actorResolver;

    @BeforeEach
    void setUp() {
        OptimizationWorkItemService service = mock(OptimizationWorkItemService.class);
        actorResolver = mock(ActorResolver.class);
        OptimizationWorkItemStalenessService healthService = mock(OptimizationWorkItemStalenessService.class);
        when(actorResolver.resolve(any(HttpServletRequest.class))).thenReturn(new Actor("admin", "admin"));
        when(healthService.health()).thenReturn(new OptimizationWorkItemStaleness(
                "DEGRADED", "OPTIMIZATION_WORK_ITEMS_STALE", Instant.parse("2026-08-25T00:00:00Z"),
                604800, 3, 1, Map.of("OPEN", 1), Map.of("owner-a", 1), Map.of("HIGH", 1), List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(new OptimizationWorkItemController(service, actorResolver, healthService))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class))).build();
    }

    @Test
    void adminCanReadStalenessHealth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/optimization-work-items/health")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.reasonCode").value("OPTIMIZATION_WORK_ITEMS_STALE"))
                .andExpect(jsonPath("$.data.staleByStatus.OPEN").value(1));
    }

    @Test
    void developerCannotReadStalenessHealth() throws Exception {
        when(actorResolver.resolve(any(HttpServletRequest.class))).thenReturn(new Actor("developer", "developer"));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-work-items/health")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden());
    }
}
