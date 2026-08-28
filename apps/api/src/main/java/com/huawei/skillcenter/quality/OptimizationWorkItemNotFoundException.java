package com.huawei.skillcenter.quality;

public class OptimizationWorkItemNotFoundException extends RuntimeException {
    public OptimizationWorkItemNotFoundException(String workItemId) {
        super("Optimization work item not found: " + workItemId);
    }
}
