package com.huawei.skillcenter.search;

import java.util.Set;
import java.util.Locale;

public record SkillSearchIndexStatus(String state, int revision, int documentCount, String sourceHash, String sourceRevision,
                                     String reasonCode) {
    private static final Set<String> STATES = Set.of("READY", "STALE", "REBUILDING", "DEGRADED", "NOT_READY");

    public SkillSearchIndexStatus(String state, int revision, int documentCount, String sourceHash, String sourceRevision) {
        this(state, revision, documentCount, sourceHash, sourceRevision, "");
    }

    public SkillSearchIndexStatus {
        state = SkillSearchDocument.boundedRequired(state, "state", 32).toUpperCase(Locale.ROOT);
        if (!STATES.contains(state)) {
            throw new IllegalArgumentException("unsupported state");
        }
        if (revision < 0 || documentCount < 0) {
            throw new IllegalArgumentException("revision and documentCount must be non-negative");
        }
        sourceHash = SkillSearchDocument.bounded(sourceHash, "sourceHash", 256);
        sourceRevision = SkillSearchDocument.bounded(sourceRevision, "sourceRevision", 128);
        reasonCode = SkillSearchDocument.bounded(reasonCode, "reasonCode", 128);
    }
}
