package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RuntimeOperationsControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void acceptsRedactedRuntimeSummaryAndAdminCanQuerySkillOperations() throws Exception {
        RuntimeSummary event = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC), "runtime-skill", "1.0.0", "success",
                42, null, "production", "team-a", "codex", "trace-123");

        mockMvc.perform(post("/api/v1/events/runtime-summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(true));

        mockMvc.perform(get("/api/v1/admin/operations/skill-runtime")
                        .param("window", "60m")
                        .param("skillId", "runtime-skill")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.total").isNumber())
                .andExpect(jsonPath("$.data.dataSource").value("all"))
                .andExpect(jsonPath("$.data.sources").isArray())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.traceRef").doesNotExist());
    }

    @Test
    void nonAdminCannotQuerySkillOperations() throws Exception {
        mockMvc.perform(get("/api/v1/admin/operations/skill-runtime")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanQueryRedactedTraceMetadataAndFilterFailures() throws Exception {
        RuntimeSummary event = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC), "trace-skill", "2.0.0", "failure",
                88, "DEPENDENCY_TIMEOUT", "production", "team-a", "codex", "trace-admin-1");

        mockMvc.perform(post("/api/v1/events/runtime-summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/admin/operations/traces")
                        .param("window", "24h")
                        .param("skillId", "trace-skill")
                        .param("status", "failure")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].traceId").value("trace-admin-1"))
                .andExpect(jsonPath("$.data[0].errorCode").value("DEPENDENCY_TIMEOUT"))
                .andExpect(jsonPath("$.data[0].operation").value("skill.run"))
                .andExpect(jsonPath("$.data[0].prompt").doesNotExist())
                .andExpect(jsonPath("$.data[0].input").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/operations/traces")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void traceQuerySupportsRuntimeMcpAndLlmFilters() throws Exception {
        String skillId = "trace-environment-" + UUID.randomUUID();
        String selectedTraceId = "trace-env-a-" + UUID.randomUUID();
        String otherTraceId = "trace-env-b-" + UUID.randomUUID();
        RuntimeSummary selected = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC), skillId, "1.0.0", "failure",
                88, "DEPENDENCY_TIMEOUT", "production", "team-a", "codex", selectedTraceId,
                "openclaw", "mcp-a", "llm-a");
        RuntimeSummary otherEnvironment = new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC), skillId, "1.0.0", "failure",
                88, "DEPENDENCY_TIMEOUT", "production", "team-a", "codex", otherTraceId,
                "openclaw", "mcp-b", "llm-a");

        mockMvc.perform(post("/api/v1/events/runtime-summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(selected)))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/events/runtime-summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherEnvironment)))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/admin/operations/traces")
                        .param("window", "24h")
                        .param("skillId", skillId)
                        .param("runtimeId", "openclaw")
                        .param("mcpServerId", "mcp-a")
                        .param("llmProviderId", "llm-a")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].traceId").value(selectedTraceId));
    }
}
