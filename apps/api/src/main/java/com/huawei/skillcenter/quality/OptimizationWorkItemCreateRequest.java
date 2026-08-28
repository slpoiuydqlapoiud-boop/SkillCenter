package com.huawei.skillcenter.quality;

public record OptimizationWorkItemCreateRequest(String skillId, String sourceVersion, String suggestionId,
                                                String hypothesis, String ownerId, String dataSource,
                                                String runtimeId, String mcpServerId, String llmProviderId,
                                                String suiteId, String suiteVersion) {
    public OptimizationWorkItemCreateRequest(String skillId, String sourceVersion, String suggestionId,
                                              String hypothesis, String ownerId, String dataSource,
                                              String runtimeId, String mcpServerId, String llmProviderId) {
        this(skillId, sourceVersion, suggestionId, hypothesis, ownerId, dataSource,
                runtimeId, mcpServerId, llmProviderId, "", "");
    }
}
