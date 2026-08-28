package com.huawei.skillcenter.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SkillQualityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void detailReturnsSafeEmptyQualityAndComparisonReasonForMissingSnapshots() throws Exception {
        mockMvc.perform(get("/api/v1/skills/missing-quality-skill/quality")
                        .param("window", "24h")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skillId").value("missing-quality-skill"))
                .andExpect(jsonPath("$.data.latestSnapshot").doesNotExist())
                .andExpect(jsonPath("$.data.runtime.totals.total").value(0))
                .andExpect(jsonPath("$.data.userId").doesNotExist());

        mockMvc.perform(get("/api/v1/skills/missing-quality-skill/quality/compare")
                        .param("baselineVersion", "1.0.0")
                        .param("candidateVersion", "1.1.0")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparable").value(false))
                .andExpect(jsonPath("$.data.reasonCode").value("NO_COMPARABLE_SNAPSHOT"));
    }

    @Test
    void invalidQualityWindowReturnsAStableRequestError() throws Exception {
        mockMvc.perform(get("/api/v1/skills/skill-a/quality")
                        .param("window", "1h")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnboundedExecutionEnvironmentFilters() throws Exception {
        mockMvc.perform(get("/api/v1/skills/skill-a/quality")
                .param("runtimeId", "customer prompt")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"));
    }

    @Test
    void qualitySuggestionsExposeEvidenceGapsWithoutCreatingAnEvaluation() throws Exception {
        mockMvc.perform(get("/api/v1/skills/skill-a/quality/suggestions")
                        .param("version", "1.0.0")
                        .param("window", "24h")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("QUALITY_DATA"))
                .andExpect(jsonPath("$.data[1].category").value("RUNTIME_DATA"))
                .andExpect(jsonPath("$.data[0].recommendedAction").isNotEmpty());
    }

    @Test
    void qualitySuggestionsRejectUnboundedExecutionEnvironmentFilters() throws Exception {
        mockMvc.perform(get("/api/v1/skills/skill-a/quality/suggestions")
                        .param("version", "1.0.0")
                        .param("window", "24h")
                        .param("runtimeId", "customer prompt")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"));
    }

    @Test
    void adminCanDispositionSuggestionAndDeveloperRemainsReadOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/skills/skill-a/quality/suggestions/runtime-data/disposition")
                        .param("version", "1.0.0")
                        .param("window", "24h")
                        .header("X-User-Id", "quality-admin")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"status\":\"ACKNOWLEDGED\",\"note\":\"补充生产样本\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispositionStatus").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.dispositionNote").value("补充生产样本"));

        mockMvc.perform(patch("/api/v1/skills/skill-a/quality/suggestions/runtime-data/disposition")
                        .param("version", "1.0.0")
                        .header("X-User-Role", "developer")
                        .contentType("application/json")
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void adminCanReadAndUpdateSuggestionThresholds() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quality/suggestion-thresholds")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minSuccessRatePercent").isNumber())
                .andExpect(jsonPath("$.data.maxP95Ms").isNumber())
                .andExpect(jsonPath("$.data.minRuntimeSamples").isNumber());

        mockMvc.perform(put("/api/v1/admin/quality/suggestion-thresholds")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"minSuccessRatePercent\":96.5,\"maxP95Ms\":900,\"minRuntimeSamples\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minSuccessRatePercent").value(96.5))
                .andExpect(jsonPath("$.data.maxP95Ms").value(900))
                .andExpect(jsonPath("$.data.minRuntimeSamples").value(8));

        mockMvc.perform(get("/api/v1/admin/quality/suggestion-thresholds")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void benchmarkHistoryIsReadableAndAdminCanCreateAnUncomparableEvidenceRecord() throws Exception {
        mockMvc.perform(get("/api/v1/skills/skill-a/quality/benchmarks")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/admin/quality/benchmarks")
                        .header("X-User-Id", "benchmark-admin")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"skillId\":\"skill-a\",\"baselineVersion\":\"1.0.0\",\"candidateVersion\":\"1.1.0\",\"window\":\"24h\",\"dataSource\":\"mock\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.skillId").value("skill-a"))
                .andExpect(jsonPath("$.data.conclusion").value("NOT_COMPARABLE"));
    }

    @Test
    void benchmarkAcceptsBoundedExecutionEnvironmentContext() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/admin/quality/benchmarks")
                        .header("X-User-Id", "benchmark-env-admin")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"skillId\":\"skill-a\",\"baselineVersion\":\"1.0.0\",\"candidateVersion\":\"1.1.0\",\"window\":\"24h\",\"dataSource\":\"mock\",\"runtimeId\":\"openclaw\",\"mcpServerId\":\"mcp-network\",\"llmProviderId\":\"llm-gateway\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.runtimeId").value("openclaw"))
                .andExpect(jsonPath("$.data.mcpServerId").value("mcp-network"))
                .andExpect(jsonPath("$.data.llmProviderId").value("llm-gateway"));
    }

    @Test
    void benchmarkRejectsUnboundedExecutionEnvironmentContext() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/admin/quality/benchmarks")
                        .header("X-User-Id", "benchmark-invalid-admin")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"skillId\":\"skill-a\",\"baselineVersion\":\"1.0.0\",\"candidateVersion\":\"1.1.0\",\"window\":\"24h\",\"runtimeId\":\"customer prompt\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVENT_SCHEMA_INVALID"));
    }
}
