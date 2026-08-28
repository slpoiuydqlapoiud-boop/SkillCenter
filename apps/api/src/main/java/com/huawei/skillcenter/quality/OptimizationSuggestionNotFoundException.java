package com.huawei.skillcenter.quality;

public class OptimizationSuggestionNotFoundException extends RuntimeException {
    public OptimizationSuggestionNotFoundException(String skillId, String version, String suggestionId) {
        super("Optimization suggestion not found: " + skillId + "/" + version + "/" + suggestionId);
    }
}
