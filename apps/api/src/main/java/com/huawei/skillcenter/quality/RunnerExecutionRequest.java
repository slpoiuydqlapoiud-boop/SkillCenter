package com.huawei.skillcenter.quality;

public record RunnerExecutionRequest(
        String skillId,
        String skillVersion,
        String evaluationRunId,
        String suiteId,
        String caseId,
        long timeoutMs,
        String scenario,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    public RunnerExecutionRequest(String skillId, String skillVersion, String evaluationRunId,
                                  String suiteId, String caseId, long timeoutMs, String scenario) {
        this(skillId, skillVersion, evaluationRunId, suiteId, caseId, timeoutMs, scenario, "", "", "");
    }

    public RunnerExecutionRequest {
        require(skillId, "skillId");
        require(skillVersion, "skillVersion");
        require(evaluationRunId, "evaluationRunId");
        require(suiteId, "suiteId");
        require(caseId, "caseId");
        if (timeoutMs <= 0 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("timeoutMs must be between 1 and 120000");
        }
        scenario = scenario == null || scenario.isBlank() ? "success" : scenario.trim().toLowerCase();
        runtimeId = environmentId(runtimeId, "runtimeId");
        mcpServerId = environmentId(mcpServerId, "mcpServerId");
        llmProviderId = environmentId(llmProviderId, "llmProviderId");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static String environmentId(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
