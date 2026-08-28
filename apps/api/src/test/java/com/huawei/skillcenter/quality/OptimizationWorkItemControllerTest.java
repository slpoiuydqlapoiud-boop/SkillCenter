package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OptimizationWorkItemControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private OptimizationWorkItemService service;
    private ActorResolver actorResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(OptimizationWorkItemService.class);
        actorResolver = mock(ActorResolver.class);
        OperationsMetricsService metrics = mock(OperationsMetricsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OptimizationWorkItemController(service, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(metrics)).build();
        when(actorResolver.resolve(any(HttpServletRequest.class))).thenReturn(new Actor("admin", "admin"));
    }

    @Test
    void adminCanCreateListUpdateStatusAndBindEvidence() throws Exception {
        OptimizationWorkItem item = item();
        when(service.create(any(OptimizationWorkItemCreateRequest.class), any(Actor.class), anyString())).thenReturn(item);
        when(service.list("skill-a", "OPEN", "owner-a", "1.0.0", new Actor("admin", "admin")))
                .thenReturn(List.of(item));
        when(service.transition(anyString(), any(OptimizationWorkItemStatusRequest.class), any(Actor.class), anyString()))
                .thenReturn(item);
        when(service.bindEvidence(anyString(), any(OptimizationWorkItemEvidenceRequest.class), any(Actor.class), anyString()))
                .thenReturn(item);

        mockMvc.perform(post("/api/v1/admin/quality/optimization-work-items")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new OptimizationWorkItemCreateRequest(
                                "skill-a", "1.0.0", "runtime-latency", "降低延迟", "owner-a",
                                "production", "runtime-a", "mcp-a", "llm-a", "suite-a", "suite-v1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workItemId").value("work-1"))
                .andExpect(jsonPath("$.data.suiteId").value("suite-a"))
                .andExpect(jsonPath("$.data.suiteVersion").value("suite-v1"));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-work-items")
                        .param("skillId", "skill-a").param("status", "OPEN")
                        .param("ownerId", "owner-a").param("sourceVersion", "1.0.0")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].suggestionId").value("runtime-latency"));

        mockMvc.perform(patch("/api/v1/admin/quality/optimization-work-items/work-1/status")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"status\":\"PLANNED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/admin/quality/optimization-work-items/work-1/evidence")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"evidenceType\":\"BENCHMARK\",\"evidenceId\":\"benchmark-1\"}"))
                .andExpect(status().isOk());
        verify(service).list("skill-a", "OPEN", "owner-a", "1.0.0", new Actor("admin", "admin"));
    }

    @Test
    void developerCannotAccessWorkItems() throws Exception {
        when(actorResolver.resolve(any(HttpServletRequest.class))).thenReturn(new Actor("developer", "developer"));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-work-items")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void notFoundUsesStableErrorCode() throws Exception {
        when(service.find("missing", new Actor("admin", "admin")))
                .thenThrow(new OptimizationWorkItemNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-work-items/missing")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OPTIMIZATION_WORK_ITEM_NOT_FOUND"));
    }

    private OptimizationWorkItem item() {
        Instant time = Instant.parse("2026-08-24T01:02:03Z");
        return new OptimizationWorkItem("work-1", "skill-a", "1.0.0", "runtime-latency",
                "运行 P95 延迟偏高", "LATENCY", "MEDIUM", List.of("p95Ms=1200"), "降低延迟",
                "owner-a", "OPEN", "", "NONE", "", "", "production", "runtime-a", "mcp-a",
                "llm-a", "suite-a", "suite-v1", "admin", time, "admin", time);
    }
}
