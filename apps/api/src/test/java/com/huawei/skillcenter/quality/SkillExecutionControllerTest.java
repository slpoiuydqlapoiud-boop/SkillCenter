package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SkillExecutionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanExecutePublishedSkillThroughTheMockRunner() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/runner/executions")
                        .header("X-User-Id", "runner-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.2.0\",\"scenario\":\"success\",\"timeoutMs\":1000,\"runtimeId\":\"openclaw\",\"mcpServerId\":\"mcp-network\",\"llmProviderId\":\"llm-gateway\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.executionId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.dataSource").value("mock"))
                .andExpect(jsonPath("$.data.runtimeId").value("openclaw"))
                .andExpect(jsonPath("$.data.mcpServerId").value("mcp-network"))
                .andExpect(jsonPath("$.data.llmProviderId").value("llm-gateway"))
                .andExpect(jsonPath("$.data.outputHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andReturn().getResponse().getContentAsString();

        String executionId = response.replaceAll(".*\\\"executionId\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mockMvc.perform(get("/api/v1/admin/runner/executions")
                        .queryParam("skillId", "eox-query")
                        .queryParam("dataSource", "mock")
                        .queryParam("runtimeId", "openclaw")
                        .queryParam("mcpServerId", "mcp-network")
                        .queryParam("llmProviderId", "llm-gateway")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].executionId").value(executionId))
                .andExpect(jsonPath("$.data[0].skillId").value("eox-query"));
    }

    @Test
    void nonAdminCannotExecuteRunnerRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/runner/executions")
                        .header("X-User-Role", "developer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.2.0\",\"scenario\":\"success\",\"timeoutMs\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void runnerRejectsUnpublishedSkillVersionBeforeExecution() throws Exception {
        mockMvc.perform(post("/api/v1/admin/runner/executions")
                        .header("X-User-Id", "runner-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"999.0.0\",\"scenario\":\"success\",\"timeoutMs\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUNNER_VERSION_NOT_ALLOWED"));
    }

    @Test
    void runnerRejectsScenariosOutsideTheMockAllowList() throws Exception {
        mockMvc.perform(post("/api/v1/admin/runner/executions")
                        .header("X-User-Id", "runner-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.2.0\",\"scenario\":\"arbitrary-script\",\"timeoutMs\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RUNNER_SCENARIO_INVALID"));
    }
}
