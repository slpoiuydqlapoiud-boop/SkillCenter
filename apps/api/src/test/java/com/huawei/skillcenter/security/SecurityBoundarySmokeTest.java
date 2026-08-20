package com.huawei.skillcenter.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "skill-center.security.rate-limit.invocation-ingest=1")
@AutoConfigureMockMvc
class SecurityBoundarySmokeTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiResponsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void crossOriginMutationIsRejectedBeforeController() throws Exception {
        mockMvc.perform(post("/api/v1/events/invocations")
                        .header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_ORIGIN_REJECTED"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void invocationIngestReturns429AfterConfiguredWindowLimit() throws Exception {
        mockMvc.perform(post("/api/v1/events/invocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/events/invocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()))
                .andExpect(header().string("X-RateLimit-Limit", "1"));
    }

    private String eventBody() {
        return "{\"schemaVersion\":\"1.0\",\"eventId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-08-18T04:00:00Z\",\"skillId\":\"eox-query\","
                + "\"version\":\"1.2.0\",\"subject\":{\"userId\":\"smoke-user\",\"teamId\":\"network-team\"},"
                + "\"client\":{\"type\":\"codex\",\"version\":\"1.0.0\"},\"sessionId\":\"smoke-session-123456\","
                + "\"status\":\"success\",\"durationMs\":42}";
    }
}
