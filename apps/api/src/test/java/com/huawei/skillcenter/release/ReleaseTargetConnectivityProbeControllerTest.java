package com.huawei.skillcenter.release;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReleaseTargetConnectivityProbeControllerTest {
    private ReleaseTargetConnectivityProbeService service;
    private ActorResolver actorResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ReleaseTargetConnectivityProbeService.class);
        actorResolver = mock(ActorResolver.class);
        when(actorResolver.resolve(any())).thenReturn(new Actor("admin", "admin"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ReleaseTargetConnectivityProbeController(actorResolver, service))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void adminCanReadSafeProbeResult() throws Exception {
        when(service.probe(any(Actor.class), eq("request-1"))).thenReturn(new ReleaseTargetProbeResult(
                "release-target", "REACHABLE", "PROBE_OK", 204, 12,
                Instant.parse("2026-08-25T00:00:00Z")));

                mockMvc.perform(post("/api/v1/admin/platform/release-target/probe")
                        .header("X-User-Role", "admin")
                        .header("X-Request-Id", "request-1")
                        .requestAttr(com.huawei.skillcenter.api.RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetId").value("release-target"))
                .andExpect(jsonPath("$.data.status").value("REACHABLE"))
                .andExpect(jsonPath("$.data.reasonCode").value("PROBE_OK"))
                .andExpect(jsonPath("$.data.endpoint").doesNotExist())
                .andExpect(jsonPath("$.data.credentialRef").doesNotExist());
    }

    @Test
    void adminCanReadSafeProbeHistoryWithBoundedLimit() throws Exception {
        when(service.history(any(Actor.class), eq(3))).thenReturn(List.of(new ReleaseTargetProbeResult(
                "release-target", "TIMEOUT", "PROBE_TIMEOUT", null, 1500,
                Instant.parse("2026-08-25T00:00:00Z"))));

        mockMvc.perform(get("/api/v1/admin/platform/release-target/probes")
                        .param("limit", "3")
                        .requestAttr(com.huawei.skillcenter.api.RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("TIMEOUT"))
                .andExpect(jsonPath("$.data[0].reasonCode").value("PROBE_TIMEOUT"))
                .andExpect(jsonPath("$.data[0].endpoint").doesNotExist())
                .andExpect(jsonPath("$.data[0].credentialRef").doesNotExist());
    }
}
