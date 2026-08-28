package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CompatibilityMatrixControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanCreateInspectAndListCompatibilityMatrix() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/quality/compatibility-matrices")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"skillId\":\"eox-query\",\"skillVersion\":\"1.3.0\",\"suiteId\":\"smoke\"," +
                                "\"runtimeIds\":[\"openclaw\"],\"mcpServerIds\":[\"mcp-network\"]," +
                                "\"llmProviderIds\":[\"llm-gateway\"],\"policy\":\"ALL_MUST_PASS\"," +
                                "\"minimumPassRate\":1,\"scenario\":\"success\",\"timeoutMs\":1000}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.matrixRunId").isNotEmpty())
                .andExpect(jsonPath("$.data.totalCases").value(1))
                .andReturn().getResponse().getContentAsString();
        String matrixId = response.replaceAll(".*\\\"matrixRunId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        String matrixStatus;
        do {
            matrixStatus = mockMvc.perform(get("/api/v1/admin/quality/compatibility-matrices/" + matrixId)
                            .header("X-User-Role", "admin"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll(".*\\\"status\\\":\\\"([^\\\"]+)\\\".*", "$1");
            if (!"COMPLETED".equals(matrixStatus)) Thread.sleep(10);
        } while (!"COMPLETED".equals(matrixStatus) && System.nanoTime() < deadline);

        mockMvc.perform(get("/api/v1/admin/quality/compatibility-matrices/" + matrixId + "/cases")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].evaluationRunId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].runtimeVersion").value("context-v1"));
        mockMvc.perform(get("/api/v1/admin/quality/compatibility-matrices")
                        .header("X-User-Role", "admin")
                        .param("skillId", "eox-query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].matrixRunId").value(matrixId));
    }

    @Test
    void nonAdminCannotCreateCompatibilityMatrix() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quality/compatibility-matrices")
                        .header("X-User-Role", "developer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.3.0\",\"suiteId\":\"smoke\","
                                + "\"runtimeIds\":[\"openclaw\"],\"mcpServerIds\":[],\"llmProviderIds\":[],"
                                + "\"policy\":\"ALL_MUST_PASS\",\"minimumPassRate\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
