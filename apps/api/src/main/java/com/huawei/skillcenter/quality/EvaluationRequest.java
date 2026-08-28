package com.huawei.skillcenter.quality;

public record EvaluationRequest(
        String skillId,
        String skillVersion,
        String suiteId,
        String scenario,
        long timeoutMs,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String suiteVersion,
        String experimentId
) {
    public EvaluationRequest(String skillId, String skillVersion, String suiteId,
                              String scenario, long timeoutMs) {
        this(skillId, skillVersion, suiteId, scenario, timeoutMs, "", "", "", "", "");
    }

    public EvaluationRequest(String skillId, String skillVersion, String suiteId,
                              String scenario, long timeoutMs,
                              String runtimeId, String mcpServerId, String llmProviderId) {
        this(skillId, skillVersion, suiteId, scenario, timeoutMs,
                runtimeId, mcpServerId, llmProviderId, "", "");
    }

    public EvaluationRequest(String skillId, String skillVersion, String suiteId,
                              String suiteVersion, String scenario, long timeoutMs) {
        this(skillId, skillVersion, suiteId, scenario, timeoutMs, "", "", "", suiteVersion, "");
    }

    public EvaluationRequest(String skillId, String skillVersion, String suiteId,
                              String scenario, long timeoutMs,
                              String runtimeId, String mcpServerId, String llmProviderId,
                              String suiteVersion) {
        this(skillId, skillVersion, suiteId, scenario, timeoutMs,
                runtimeId, mcpServerId, llmProviderId, suiteVersion, "");
    }

    public EvaluationRequest {
        if (timeoutMs == 0) {
            timeoutMs = 1_000;
        }
        runtimeId = environmentId(runtimeId, "runtimeId");
        mcpServerId = environmentId(mcpServerId, "mcpServerId");
        llmProviderId = environmentId(llmProviderId, "llmProviderId");
        suiteVersion = suiteVersion == null ? "" : suiteVersion.trim();
        experimentId = experimentId == null ? "" : experimentId.trim();
        if (!experimentId.isBlank() && !experimentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("experimentId must be a bounded identifier");
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
