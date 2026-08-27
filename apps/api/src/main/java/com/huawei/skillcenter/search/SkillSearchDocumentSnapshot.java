package com.huawei.skillcenter.search;

import java.util.List;

public record SkillSearchDocumentSnapshot(List<SkillSearchDocument> documents, String sourceHash, long sourceRevision) {
    public SkillSearchDocumentSnapshot {
        if (documents == null || documents.stream().anyMatch(document -> document == null)) {
            throw new IllegalArgumentException("documents must not contain null values");
        }
        documents = List.copyOf(documents);
        sourceHash = SkillSearchDocument.boundedRequired(sourceHash, "sourceHash", 256);
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must be non-negative");
        }
    }
}
