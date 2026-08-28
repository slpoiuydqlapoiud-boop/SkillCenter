package com.huawei.skillcenter.search;

import java.util.Locale;
import java.util.Set;
import java.time.Instant;

public record SkillSearchIndexStatus(String state, int revision, int documentCount, String sourceHash, String sourceRevision,
                                     Instant indexedAt, String reasonCode) {
    private static final Set<String> STATES = Set.of("READY", "STALE", "REBUILDING", "DEGRADED", "NOT_READY");

    public SkillSearchIndexStatus(String state, int revision, int documentCount, String sourceHash, String sourceRevision) {
        this(state, revision, documentCount, sourceHash, sourceRevision, null, "");
    }

    public SkillSearchIndexStatus(String state, int revision, int documentCount, String sourceHash, String sourceRevision,
                                  String reasonCode) {
        this(state, revision, documentCount, sourceHash, sourceRevision, null, reasonCode);
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
