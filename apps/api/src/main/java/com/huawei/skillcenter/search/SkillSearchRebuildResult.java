package com.huawei.skillcenter.search;

public record SkillSearchRebuildResult(int revision, int documentCount, String sourceHash, String sourceRevision) {
    public SkillSearchRebuildResult {
        if (revision < 0 || documentCount < 0) {
            throw new IllegalArgumentException("revision and documentCount must be non-negative");
        }
        sourceHash = SkillSearchDocument.boundedRequired(sourceHash, "sourceHash", 256);
        sourceRevision = SkillSearchDocument.bounded(sourceRevision, "sourceRevision", 128);
    }
}
