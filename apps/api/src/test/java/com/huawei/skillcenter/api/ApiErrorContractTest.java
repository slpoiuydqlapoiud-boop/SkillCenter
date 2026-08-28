package com.huawei.skillcenter.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiErrorContractTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownRouteReturnsStableErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void invalidSkillPageParameterReturnsBadRequestInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/skills").param("page", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].path").value("page"));
    }

    @Test
    void unknownDistributionTokenReturnsGoneInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/distribution/artifacts/eox-query/1.2.0")
                        .param("token", "unknown-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("DISTRIBUTION_AUTHORIZATION_INVALID"));
    }

    @Test
    void unauthorizedInstallationDetailUsesNotFoundContract() throws Exception {
        mockMvc.perform(get("/api/v1/installations/installation-does-not-exist")
                        .header("X-User-Id", "viewer-1")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INSTALLATION_NOT_FOUND"));
    }

    @Test
    void sameInvocationEventIdWithDifferentContentReturnsConflict() throws Exception {
        String first = """
                {"schemaVersion":"1.0","eventId":"4d8f1d8f-68a2-4c50-a4d5-ec5f6d1f9fd2","occurredAt":"2026-08-17T15:00:00+08:00","skillId":"eox-query","version":"1.2.0","subject":{"userId":"contract-user","teamId":"network-team"},"client":{"type":"codex","version":"1.0.0"},"sessionId":"session_1234567890","status":"success","durationMs":42}
                """;
        String second = """
                {"schemaVersion":"1.0","eventId":"4d8f1d8f-68a2-4c50-a4d5-ec5f6d1f9fd2","occurredAt":"2026-08-17T15:00:01+08:00","skillId":"eox-query","version":"1.2.0","subject":{"userId":"contract-user","teamId":"network-team"},"client":{"type":"codex","version":"1.0.0"},"sessionId":"session_1234567890","status":"failure","durationMs":42,"errorCode":"UPSTREAM_TIMEOUT"}
                """;

        mockMvc.perform(post("/api/v1/events/invocations").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/events/invocations").contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_ID_CONFLICT"));
    }

    @Test
    void crossOriginMutationReturnsCsrfOriginContract() throws Exception {
        mockMvc.perform(post("/api/v1/events/invocations")
                        .header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_ORIGIN_REJECTED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void lifecycleImportRejectsUnexpectedJsonFieldsWithSchemaEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/admin/skill-lifecycle/projection/import")
                        .header("X-User-Id", "lifecycle-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","actor":"admin","token":"secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}

@SpringBootTest
@AutoConfigureMockMvc
class SensitiveResponseContractTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void lifecycleProjectionStatusDoesNotExposeSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/v1/admin/skill-lifecycle/projection/status")
                        .header("X-User-Id", "lifecycle-admin")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actor").doesNotExist())
                .andExpect(jsonPath("$.data.requestId").doesNotExist())
                .andExpect(jsonPath("$.data.path").doesNotExist())
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.trace").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.dbSettings").doesNotExist());
    }
}
