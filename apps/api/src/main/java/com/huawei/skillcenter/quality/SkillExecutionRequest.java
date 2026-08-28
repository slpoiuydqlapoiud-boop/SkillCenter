package com.huawei.skillcenter.quality;

import java.util.Set;

public record SkillExecutionRequest(
        String skillId,
        String skillVersion,
        String scenario,
        long timeoutMs,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    public static final Set<String> ALLOWED_SCENARIOS = Set.of("success", "failure", "timeout", "cancel", "cancelled");

    public SkillExecutionRequest(String skillId, String skillVersion, String scenario, long timeoutMs) {
        this(skillId, skillVersion, scenario, timeoutMs, "", "", "");
    }

    public SkillExecutionRequest {
        require(skillId, "skillId");
        require(skillVersion, "skillVersion");
        scenario = scenario == null || scenario.isBlank() ? "success" : scenario.trim().toLowerCase();
        if (!ALLOWED_SCENARIOS.contains(scenario)) {
            throw new RunnerScenarioNotAllowedException(scenario);
        }
        if (timeoutMs <= 0 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("timeoutMs must be between 1 and 120000");
        }
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
