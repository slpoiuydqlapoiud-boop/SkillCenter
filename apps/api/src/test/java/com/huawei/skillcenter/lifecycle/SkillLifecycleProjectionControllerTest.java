package com.huawei.skillcenter.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillLifecycleProjectionControllerTest {
    private static final String REQUEST_ID = "request-task4";
    private static final Actor ADMIN = new Actor("admin-user", "admin");
    private static final Actor VIEWER = new Actor("viewer-user", "viewer");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private SkillLifecycleProjectionService service;
    private ActorResolver actorResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SkillLifecycleProjectionService.class);
        actorResolver = mock(ActorResolver.class);
        when(actorResolver.resolve(any())).thenReturn(ADMIN);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillLifecycleProjectionController(service, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .setValidator(validator)
                .build();
    }

    @Test
    void adminCanReadStatusPreflightSkillsAndImpactWithOnlySafeMetadata() throws Exception {
        when(service.status()).thenReturn(SkillLifecycleProjectionStatus.ready(
                "json", "2", 4L, "a".repeat(64), 3, 5, 2, 2, 1));
        when(service.preflight()).thenReturn(new SkillLifecycleProjectionPreflight(
                "json", 4L, "a".repeat(64), "b".repeat(64),
                Instant.parse("2026-08-25T01:00:00Z"), 3, 5, 2, 2, 1,
                false, "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED"));
        when(service.reconciliation()).thenReturn(new SkillLifecycleProjectionReconciliation(
                "postgresql", "DRIFTED", "SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED", "2", 4L,
                "a".repeat(64), "b".repeat(64), Instant.parse("2026-08-25T01:00:00Z"),
                Instant.parse("2026-08-25T00:50:00Z"), Instant.parse("2026-08-25T01:00:00Z"),
                600L, 900L,
                new SkillLifecycleProjectionCounts(3, 5, 2, 2, 1),
                new SkillLifecycleProjectionCounts(2, 4, 2, 2, 1),
                new SkillLifecycleProjectionCountDelta(1, 1, 0, 0, 0)));
        when(service.findSkills(eq(new SkillLifecycleProjectionQuery(
                "skill-a", "1.0.0", "PUBLISHED", ReleaseEnvironment.PRODUCTION, 2, 10)), eq(ADMIN)))
                .thenReturn(List.of(new SkillLifecycleProjectionView(
                        "skill-a",
                        "1.0.0",
                        "pkg-skill-a",
                        "PUBLISHED",
                        "1.0.0",
                        "PUBLISHED",
                        1,
                        1,
                        1,
                        "PUBLIC",
                        "team-alpha",
                        List.of(new SkillLifecycleProjectionView.ReleaseView(
                                "release-1",
                                "PRODUCTION",
                                "PROMOTED",
                                "PASSED")))));
        when(service.findImpact("skill-a", "1.0.0", ADMIN))
                .thenReturn(new SkillLifecycleImpactView(
                        "skill-a",
                        "1.0.0",
                        false,
                        List.of(new SkillLifecycleImpactView.Node(
                                "relation-1",
                                "skill-b",
                                "2.0.0",
                                "DEPENDS_ON",
                                "ACTIVE",
                                1,
                                true,
                                List.of(new SkillLifecycleProjectionView.ReleaseView(
                                        "release-2",
                                        "PRODUCTION",
                                        "PROMOTED",
                                        "PASSED"))))));

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/status")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.backend").value("json"))
                .andExpect(jsonPath("$.data.revision").value(4))
                .andExpect(jsonPath("$.data.sourceSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.sourceGeneratedAt").doesNotExist())
                .andExpect(jsonPath("$.data.actor").doesNotExist())
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.path").doesNotExist())
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.trace").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.dbSettings").doesNotExist());

        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/preflight")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.sourceSha256").value("b".repeat(64)))
                .andExpect(jsonPath("$.data.reasonCode").value("SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED"))
                .andExpect(jsonPath("$.data.actor").doesNotExist())
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.trace").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/reconciliation")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.state").value("DRIFTED"))
                .andExpect(jsonPath("$.data.reasonCode").value("SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED"))
                .andExpect(jsonPath("$.data.countDelta.skillCount").value(1))
                .andExpect(jsonPath("$.data.actor").doesNotExist())
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/skills")
                        .param("skillId", "skill-a")
                        .param("version", "1.0.0")
                        .param("status", "published")
                        .param("environment", "PRODUCTION")
                        .param("page", "2")
                        .param("pageSize", "10")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data[0].skillId").value("skill-a"))
                .andExpect(jsonPath("$.data[0].releases[0].releaseId").value("release-1"))
                .andExpect(jsonPath("$.data[0].ownerTeamId").doesNotExist())
                .andExpect(content().string(not(containsString("team-alpha"))))
                .andExpect(jsonPath("$.data[0].actor").doesNotExist())
                .andExpect(jsonPath("$.data[0].requestId").doesNotExist())
                .andExpect(jsonPath("$.data[0].path").doesNotExist())
                .andExpect(jsonPath("$.data[0].prompt").doesNotExist())
                .andExpect(jsonPath("$.data[0].trace").doesNotExist())
                .andExpect(jsonPath("$.data[0].token").doesNotExist())
                .andExpect(jsonPath("$.data[0].dbSettings").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/skills/skill-a/impact")
                        .param("version", "1.0.0")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.rootSkillId").value("skill-a"))
                .andExpect(jsonPath("$.data.nodes[0].skillId").value("skill-b"))
                .andExpect(jsonPath("$.data.nodes[0].releases[0].releaseId").value("release-2"))
                .andExpect(jsonPath("$.data.nodes[0].actor").doesNotExist())
                .andExpect(jsonPath("$.data.nodes[0].requestId").doesNotExist())
                .andExpect(jsonPath("$.data.nodes[0].path").doesNotExist())
                .andExpect(jsonPath("$.data.nodes[0].prompt").doesNotExist())
                .andExpect(jsonPath("$.data.nodes[0].trace").doesNotExist())
                .andExpect(jsonPath("$.data.nodes[0].token").doesNotExist())
                .andExpect(jsonPath("$.data.nodes[0].dbSettings").doesNotExist());

        verify(service).status();
        verify(service).preflight();
        verify(service).reconciliation();
        verify(service).findSkills(new SkillLifecycleProjectionQuery(
                "skill-a", "1.0.0", "PUBLISHED", ReleaseEnvironment.PRODUCTION, 2, 10), ADMIN);
        verify(service).findImpact("skill-a", "1.0.0", ADMIN);
        verify(actorResolver, org.mockito.Mockito.times(5)).resolve(any());
    }

    @Test
    void adminCanImportProjectionHashUsingActorAndRequestIdOnly() throws Exception {
        when(service.importSnapshot("a".repeat(64), ADMIN, REQUEST_ID))
                .thenReturn(new SkillLifecycleProjectionImportResult(
                        true, false, 5L, "a".repeat(64), 3, 5, 2, 2, 1, ""));

        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/import")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SkillLifecycleProjectionImportRequest("a".repeat(64)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.imported").value(true))
                .andExpect(jsonPath("$.data.revision").value(5))
                .andExpect(jsonPath("$.data.sourceSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.actor").doesNotExist())
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.path").doesNotExist())
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.trace").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.dbSettings").doesNotExist());

        verify(service).importSnapshot("a".repeat(64), ADMIN, REQUEST_ID);
        verify(actorResolver).resolve(any());
    }

    @Test
    void everyRouteRequiresAdminRoleAndSkipsServiceForNonAdmins() throws Exception {
        when(actorResolver.resolve(any())).thenReturn(VIEWER);

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/status")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));

        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/preflight")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/reconciliation")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/import")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSha256\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/skills")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/skills/skill-a/impact")
                        .param("version", "1.0.0")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
        verify(actorResolver, org.mockito.Mockito.times(6)).resolve(any());
    }

    @Test
    void importRejectsUppercaseHashAndUnexpectedJsonFields() throws Exception {
        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/import")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSha256\":\"" + "A".repeat(64) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"))
                .andExpect(jsonPath("$.error.message").value("Request body does not match the allowed event schema"));

        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/import")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSha256\":\"" + "a".repeat(64) + "\",\"actor\":\"admin\",\"token\":\"secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"))
                .andExpect(content().string(not(containsString("secret"))));

        verify(service, never()).importSnapshot(any(), any(), any());
    }

    @Test
    void skillsRejectsUnsupportedLifecycleStatusWithInvalidRequestEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/skills")
                        .param("status", "not-a-status")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));

        verify(service, never()).findSkills(any(), any());
    }

    @Test
    void sourceInvalidMapsToSafeGenericLifecycleError() throws Exception {
        when(service.preflight()).thenThrow(new SkillLifecycleProjectionSourceInvalidException());

        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/preflight")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID"))
                .andExpect(jsonPath("$.error.message").value("Skill lifecycle projection source is invalid"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(content().string(not(containsString("Prompt"))))
                .andExpect(content().string(not(containsString("Trace"))))
                .andExpect(content().string(not(containsString("token"))))
                .andExpect(content().string(not(containsString("db"))))
                .andExpect(content().string(not(containsString("path"))));
    }
}
