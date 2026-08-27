package com.huawei.skillcenter.search;

public record SkillSearchIndexStatus(String state, int revision, int documentCount, String sourceHash, String sourceRevision) {
    public SkillSearchIndexStatus {
        state = SkillSearchDocument.boundedRequired(state, "state", 32);
        if (revision < 0 || documentCount < 0) {
            throw new IllegalArgumentException("revision and documentCount must be non-negative");
        }
        sourceHash = SkillSearchDocument.bounded(sourceHash, "sourceHash", 256);
        sourceRevision = SkillSearchDocument.bounded(sourceRevision, "sourceRevision", 128);
    }
}
