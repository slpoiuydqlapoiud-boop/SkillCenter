package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InstallationAuditControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void installationIsPersistedAndAudited() throws Exception {
        mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}")
                        .header("X-User-Id", "alice")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/installations")
                        .header("X-User-Id", "alice")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data[-1].requestedBy").value("alice"));

        MvcResult audit = mockMvc.perform(get("/api/v1/audit")
                        .header("X-User-Id", "auditor")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode entries = objectMapper.readTree(audit.getResponse().getContentAsString()).path("data");
        boolean found = false;
        for (JsonNode entry : entries) {
            if ("INSTALL_REQUESTED".equals(entry.path("action").asText())
                    && "alice".equals(entry.path("actorId").asText())) {
                found = true;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found);
    }

    @Test
    void viewerCannotReadAuditLog() throws Exception {
        mockMvc.perform(get("/api/v1/audit").header("X-User-Role", "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
