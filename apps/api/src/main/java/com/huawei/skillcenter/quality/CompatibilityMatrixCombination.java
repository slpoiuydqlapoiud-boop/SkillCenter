package com.huawei.skillcenter.quality;

public record CompatibilityMatrixCombination(String runtimeId, String mcpServerId, String llmProviderId) {
    public CompatibilityMatrixCombination {
        runtimeId = normalize(runtimeId, "runtimeId");
        mcpServerId = normalize(mcpServerId, "mcpServerId");
        llmProviderId = normalize(llmProviderId, "llmProviderId");
    }

    private static String normalize(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
