package com.huawei.skillcenter.quality;

public record OptimizationExperimentCreateRequest(String workItemId) {
    public OptimizationExperimentCreateRequest {
        if (workItemId == null || workItemId.isBlank()
                || !workItemId.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("workItemId must be a bounded identifier");
        }
        workItemId = workItemId.trim();
    }
}
