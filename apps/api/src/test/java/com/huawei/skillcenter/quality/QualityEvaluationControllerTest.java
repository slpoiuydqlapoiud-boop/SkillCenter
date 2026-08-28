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

import java.time.Duration;

@SpringBootTest
@AutoConfigureMockMvc
class QualityEvaluationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanSubmitAndReadMockEvaluation() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/quality/evaluations")
                        .header("X-User-Id", "quality-admin")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.2.0\",\"suiteId\":\"smoke\",\"scenario\":\"success\",\"timeoutMs\":1000}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.dataSource").value("mock"))
                .andReturn().getResponse().getContentAsString();

        String runId = response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        String statusValue;
        do {
            statusValue = mockMvc.perform(get("/api/v1/admin/quality/evaluations/" + runId)
                            .header("X-User-Role", "admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(runId))
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll(".*\\\"status\\\":\\\"([^\\\"]+)\\\".*", "$1");
            if (!"COMPLETED".equals(statusValue)) {
                Thread.sleep(10);
            }
        } while (!"COMPLETED".equals(statusValue) && System.nanoTime() < deadline);
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", statusValue);
    }

    @Test
    void nonAdminCannotRunEvaluation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quality/evaluations")
                        .header("X-User-Role", "developer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.2.0\",\"suiteId\":\"smoke\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanInspectProviderCapabilities() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/providers")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("mock-runner"))
                .andExpect(jsonPath("$.data[0].version").value("1.0"))
                .andExpect(jsonPath("$.data[0].status").value("UP"))
                .andExpect(jsonPath("$.data[1].id").value("mock-evaluation"));
    }

    @Test
    void adminCanInspectReservedExternalProviderContracts() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/provider-contracts")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("openclaw-runner"))
                .andExpect(jsonPath("$.data[0].status").value("CONTRACT_ONLY"))
                .andExpect(jsonPath("$.data[1].id").value("deepeval-evaluation"))
                .andExpect(jsonPath("$.data[2].id").value("langfuse-observability"));
    }

    @Test
    void adminCanInspectProviderContractVerificationWithoutExposingSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/provider-contract-verification")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].providerId").value("openclaw-runner"))
                .andExpect(jsonPath("$.data[0].verificationStatus").value("NOT_REGISTERED"))
                .andExpect(jsonPath("$.data[0].reason").value("EXTERNAL_PROVIDER_NOT_REGISTERED"))
                .andExpect(jsonPath("$.data[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data[0].configReference").doesNotExist());
    }

    @Test
    void adminCanInspectProviderReadinessSummaryWithoutExposingSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/provider-readiness")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.activeProviderCount").value(3))
                .andExpect(jsonPath("$.data.healthyProviderCount").value(3))
                .andExpect(jsonPath("$.data.contractOnlyProviderCount").value(3))
                .andExpect(jsonPath("$.data.contractOnlyProviderIds[0]").value("deepeval-evaluation"))
                .andExpect(jsonPath("$.data.contractOnlyProviderIds[1]").value("langfuse-observability"))
                .andExpect(jsonPath("$.data.contractOnlyProviderIds[2]").value("openclaw-runner"))
                .andExpect(jsonPath("$.data.reason").value("EXTERNAL_PROVIDERS_CONTRACT_ONLY"))
                .andExpect(jsonPath("$.data.reasonDescription").value("外部 Provider 尚未启用真实适配器"))
                .andExpect(jsonPath("$.data.secret").doesNotExist());
    }

    @Test
    void adminCanReadSuitesAndQualityRules() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/suites")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("smoke"))
                .andExpect(jsonPath("$.data[0].enabled").value(true));
        mockMvc.perform(get("/api/v1/admin/quality/rules")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("quality-v1"))
                .andExpect(jsonPath("$.data.minScore").isNumber());
    }

    @Test
    void cancelEndpointIsIdempotentForCompletedEvaluation() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/quality/evaluations")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.2.0\",\"suiteId\":\"smoke\",\"scenario\":\"success\",\"timeoutMs\":1000}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        String statusValue;
        do {
            statusValue = mockMvc.perform(get("/api/v1/admin/quality/evaluations/" + runId)
                            .header("X-User-Role", "admin"))
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll(".*\\\"status\\\":\\\"([^\\\"]+)\\\".*", "$1");
            if (!"COMPLETED".equals(statusValue)) Thread.sleep(10);
        } while (!"COMPLETED".equals(statusValue) && System.nanoTime() < deadline);

        mockMvc.perform(post("/api/v1/admin/quality/evaluations/" + runId + "/cancel")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(runId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void adminCanInspectRedactedCaseResults() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/quality/evaluations")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"eox-query\",\"skillVersion\":\"1.2.0\",\"suiteId\":\"smoke\",\"scenario\":\"success\",\"timeoutMs\":1000}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        String statusValue;
        do {
            statusValue = mockMvc.perform(get("/api/v1/admin/quality/evaluations/" + runId)
                            .header("X-User-Role", "admin"))
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll(".*\\\"status\\\":\\\"([^\\\"]+)\\\".*", "$1");
            if (!"COMPLETED".equals(statusValue)) Thread.sleep(10);
        } while (!"COMPLETED".equals(statusValue) && System.nanoTime() < deadline);

        mockMvc.perform(get("/api/v1/admin/quality/evaluations/" + runId + "/results")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].caseId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].passed").value(true))
                .andExpect(jsonPath("$.data[0].dataSource").value("mock"));
    }

    @Test
    void nonAdminCannotCancelEvaluation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/quality/evaluations/missing/cancel")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
