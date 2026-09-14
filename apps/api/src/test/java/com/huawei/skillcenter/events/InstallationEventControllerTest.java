package com.huawei.skillcenter.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InstallationEventControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void installationBatchUpdatesStatusAndDetailCanBeReadByOwner() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}")
                        .header("X-User-Id", "event-owner")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        String installationId = objectMapper.readTree(creation.getResponse().getContentAsString())
                .path("data").path("installationId").asText();

        mockMvc.perform(post("/api/v1/events/installations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(installationId))
                        .header("X-User-Id", "event-owner")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected").value(0));

        mockMvc.perform(get("/api/v1/installations/{installationId}", installationId)
                        .header("X-User-Id", "event-owner")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("installed"));
    }

    @Test
    void installationUpgradeEventAcceptsTheSchemaFromVersionField() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}")
                        .header("X-User-Id", "upgrade-owner")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        String installationId = objectMapper.readTree(creation.getResponse().getContentAsString())
                .path("data").path("installationId").asText();

        mockMvc.perform(post("/api/v1/events/installations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upgradeBatch(installationId))
                        .header("X-User-Id", "upgrade-owner")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected").value(0));
    }

    @Test
    void installationBatchRejectsGatewayClientTypeAtTheSchemaBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/events/installations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch("installation-not-used").replace("\"type\":\"codex\"", "\"type\":\"gateway\""))
                        .header("X-User-Id", "event-owner")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.results[0].errorCode").value("EVENT_SCHEMA_INVALID"));
    }

    @Test
    void installationDetailIsNotVisibleToAnotherViewer() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}")
                        .header("X-User-Id", "owner-a")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isCreated())
                .andReturn();
        String installationId = objectMapper.readTree(creation.getResponse().getContentAsString())
                .path("data").path("installationId").asText();

        mockMvc.perform(get("/api/v1/installations/{installationId}", installationId)
                        .header("X-User-Id", "owner-b")
                        .header("X-User-Role", "viewer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INSTALLATION_NOT_FOUND"));
    }

    private String batch(String installationId) {
        String eventId = UUID.randomUUID().toString();
        return """
                {
                  "batchId":"batch-install-1",
                  "schemaVersion":"1.0",
                  "events":[{
                    "schemaVersion":"1.0",
                    "eventId":"%s",
                    "occurredAt":"2026-08-17T16:01:00+08:00",
                    "skillId":"eox-query",
                    "version":"1.2.0",
                    "subject":{"userId":"event-owner","teamId":"network-team"},
                    "client":{"type":"codex","version":"1.0.0"},
                    "deviceId":"device_123456789012",
                    "action":"install",
                    "method":"one-click",
                    "outcome":"success"
                  }]
                }
                """.formatted(eventId);
    }

    private String upgradeBatch(String installationId) {
        return batch(installationId).replace("\"action\":\"install\"", "\"action\":\"upgrade\",\"fromVersion\":\"1.1.0\"")
                .replace("event-owner", "upgrade-owner");
    }
}
