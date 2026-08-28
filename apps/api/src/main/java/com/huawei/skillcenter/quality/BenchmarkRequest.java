package com.huawei.skillcenter.quality;

public record BenchmarkRequest(
        String skillId,
        String baselineVersion,
        String candidateVersion,
        String window,
        String dataSource,
        String runtimeId,
        String mcpServerId,
        String llmProviderId,
        String suiteId,
        String suiteVersion,
        String experimentId
) {
    public BenchmarkRequest(String skillId, String baselineVersion, String candidateVersion,
                            String window, String dataSource, String runtimeId, String mcpServerId,
                            String llmProviderId, String suiteId, String suiteVersion) {
        this(skillId, baselineVersion, candidateVersion, window, dataSource, runtimeId, mcpServerId,
                llmProviderId, suiteId, suiteVersion, "");
    }

    public BenchmarkRequest(String skillId, String baselineVersion, String candidateVersion,
                            String window, String dataSource) {
        this(skillId, baselineVersion, candidateVersion, window, dataSource, "", "", "", "", "", "");
    }

    public BenchmarkRequest(String skillId, String baselineVersion, String candidateVersion,
                            String window, String dataSource,
                            String runtimeId, String mcpServerId, String llmProviderId) {
        this(skillId, baselineVersion, candidateVersion, window, dataSource,
                runtimeId, mcpServerId, llmProviderId, "", "", "");
    }

    public BenchmarkRequest {
        runtimeId = environmentId(runtimeId);
        mcpServerId = environmentId(mcpServerId);
        llmProviderId = environmentId(llmProviderId);
        suiteId = suiteId == null ? "" : suiteId.trim();
        suiteVersion = suiteVersion == null ? "" : suiteVersion.trim();
        experimentId = experimentId == null ? "" : experimentId.trim();
        if (!experimentId.isBlank() && !experimentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("experimentId must be a bounded identifier");
        }
    }

    private static String environmentId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "";
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("execution environment identifier is invalid");
        }
        return normalized;
    }
}
