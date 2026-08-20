package com.huawei.skillcenter.distribution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DistributionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void installationResponseContainsHashAndCompatibility() throws Exception {
        mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.skill.id").value("eox-query"))
                .andExpect(jsonPath("$.data.artifact.sha256").value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.data.compatibility").exists());
    }

    @Test
    void installationResponseContainsShortLivedAuthorizationAndCliCommand() throws Exception {
        mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"cli\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.manifest.skill.id").value("eox-query"))
                .andExpect(jsonPath("$.data.installationId").isNotEmpty())
                .andExpect(jsonPath("$.data.authorization.tokenId").isNotEmpty())
                .andExpect(jsonPath("$.data.authorization.token").isNotEmpty())
                .andExpect(jsonPath("$.data.authorization.method").value("cli"))
                .andExpect(jsonPath("$.data.authorization.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.cliCommand").value(matchesPattern("skillctl install --skill eox-query@.+ --token .+")));
    }

    @Test
    void unknownSkillCannotProduceInstallationManifest() throws Exception {
        mockMvc.perform(post("/api/v1/skills/missing/installations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SKILL_NOT_FOUND"));
    }

    @Test
    void repeatedInstallationKeyIsReplayAndDifferentPayloadConflicts() throws Exception {
        String key = "installation-idempotency-1";
        String first = "{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"cli\"}";
        String different = "{\"clientType\":\"codex\",\"clientVersion\":\"2.0.0\",\"method\":\"cli\"}";

        mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        mockMvc.perform(post("/api/v1/skills/eox-query/installations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }
}
