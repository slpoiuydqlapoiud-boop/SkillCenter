package com.huawei.skillcenter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.QualityGateBlockedException;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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

class ReleaseControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ReleaseService service;
    private ActorResolver actorResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ReleaseService.class);
        actorResolver = mock(ActorResolver.class);
        when(actorResolver.resolve(any())).thenReturn(new Actor("admin", "admin"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ReleaseController(service, actorResolver)).build();
    }

    @Test
    void listsReleasesWithFiltersAndRequestIdEnvelope() throws Exception {
        ReleaseRecord release = release();
        when(service.list(eq("skill-a"), eq("1.0.0"), eq(ReleaseEnvironment.STAGING), eq(ReleaseStatus.REQUESTED), any()))
                .thenReturn(List.of(release));

        mockMvc.perform(get("/api/v1/admin/releases")
                        .param("skillId", "skill-a")
                        .param("version", "1.0.0")
                        .param("targetEnvironment", "STAGING")
                        .param("status", "REQUESTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].releaseId").value("release-1"))
                .andExpect(jsonPath("$.data[0].targetEnvironment").value("STAGING"))
                .andExpect(jsonPath("$.requestId").value("null"));
    }

    @Test
    void createsReleaseUsingExplicitRequestBody() throws Exception {
        ReleaseRecord release = release();
        when(service.request(any(ReleaseRequest.class), any(Actor.class), any(String.class))).thenReturn(release);

        mockMvc.perform(post("/api/v1/admin/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReleaseRequest("skill-a", "1.0.0",
                                ReleaseEnvironment.STAGING, "", "idem-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    void productionReadinessBlockUsesStableDetailsWithoutRawCause() throws Exception {
        when(service.request(any(ReleaseRequest.class), any(Actor.class), any(String.class)))
                .thenThrow(new QualityGateBlockedException("skill-a", "1.0.0",
                        List.of("PLATFORM_PRODUCTION_READINESS_BLOCKED", "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED")));
        mockMvc = MockMvcBuilders.standaloneSetup(new ReleaseController(service, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();

        mockMvc.perform(post("/api/v1/admin/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReleaseRequest("skill-a", "1.0.0",
                                ReleaseEnvironment.PRODUCTION, "", "prod-blocked"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUALITY_GATE_BLOCKED"))
                .andExpect(jsonPath("$.error.details[0].reason").value("PLATFORM_PRODUCTION_READINESS_BLOCKED"))
                .andExpect(jsonPath("$.error.details[1].reason").value("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("credential"))));
    }

    @Test
    void exposesReadOnlyAdmissionDecisionForAdministrators() throws Exception {
        ReleaseAdmissionService admission = mock(ReleaseAdmissionService.class);
        when(admission.evaluate("skill-a", "1.0.0"))
                .thenReturn(new ReleaseAdmissionDecision(true, ReleaseAdmissionMode.CONTROLLED,
                        "RELEASE_PROMOTED", "release-1", false));
        mockMvc = MockMvcBuilders.standaloneSetup(new ReleaseController(service, admission, actorResolver)).build();

        mockMvc.perform(get("/api/v1/admin/releases/admission")
                        .param("skillId", "skill-a").param("version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.mode").value("CONTROLLED"))
                .andExpect(jsonPath("$.data.releaseId").value("release-1"));
    }

    private ReleaseRecord release() {
        return ReleaseRecord.request("release-1", "skill-a", "1.0.0", "sha-1", ReleaseEnvironment.STAGING,
                ReleaseGateSnapshot.passed(Instant.parse("2026-08-24T01:00:00Z")), "idem-1", "admin",
                Instant.parse("2026-08-24T01:00:00Z"));
    }
}
