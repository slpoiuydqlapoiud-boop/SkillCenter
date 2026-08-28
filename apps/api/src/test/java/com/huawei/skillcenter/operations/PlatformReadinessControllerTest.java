package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformReadinessControllerTest {
    private MockMvc mockMvc;
    private ActorResolver actorResolver;

    @BeforeEach
    void setUp() {
        actorResolver = mock(ActorResolver.class);
        PlatformReadinessService service = mock(PlatformReadinessService.class);
        when(actorResolver.resolve(any())).thenReturn(new Actor("admin-user", "admin"));
        when(service.readiness()).thenReturn(new PlatformReadiness(
                "NOT_READY", "PRODUCTION_HANDOFF", Instant.parse("2026-08-25T00:00:00Z"),
                List.of(new PlatformReadiness.Component(
                        "PRODUCTION_EXTERNAL_EVIDENCE", "NOT_READY",
                        "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED", "生产外部依赖与上线证据尚未完成核验")),
                List.of("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED")));
        mockMvc = MockMvcBuilders.standaloneSetup(new PlatformReadinessController(service, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void adminCanReadAggregatedProductionHandoffStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/platform/readiness")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-platform-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("request-platform-1"))
                .andExpect(jsonPath("$.data.overall").value("NOT_READY"))
                .andExpect(jsonPath("$.data.scope").value("PRODUCTION_HANDOFF"))
                .andExpect(jsonPath("$.data.components[0].reasonCode")
                        .value("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED"))
                .andExpect(jsonPath("$.data.endpoint").doesNotExist())
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(content().string(not(containsString("/secret"))));
    }

    @Test
    void nonAdminCannotReadAggregatedReadiness() throws Exception {
        when(actorResolver.resolve(any())).thenReturn(new Actor("viewer-user", "viewer"));

        mockMvc.perform(get("/api/v1/admin/platform/readiness")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-platform-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
