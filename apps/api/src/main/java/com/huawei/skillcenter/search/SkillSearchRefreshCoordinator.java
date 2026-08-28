package com.huawei.skillcenter.search;

/** Coordinates bounded, in-process refreshes while preserving the last committed index. */
public final class SkillSearchRefreshCoordinator {
    private static final String CONFLICT = "SEARCH_INDEX_SOURCE_CONFLICT";
    private static final String REBUILD_FAILED = "SEARCH_INDEX_REBUILD_FAILED";

    private final SkillSearchIndex index;
    private final SkillSearchDocumentSource source;
    private volatile String sourceRevision = "";
    private volatile String reasonCode = "";

    public SkillSearchRefreshCoordinator(SkillSearchIndex index, SkillSearchDocumentSource source) {
        if (index == null || source == null) {
            throw new IllegalArgumentException("index and source are required");
        }
        this.index = index;
        this.source = source;
    }

    public void invalidate(SkillSearchRefreshEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        index.invalidate(event.reasonCode());
        sourceRevision = Long.toString(event.sourceRevision());
        reasonCode = "";
    }

    public synchronized SkillSearchRebuildResult ensureReady() {
        if (!index.status().sourceHash().isEmpty()) {
            return result(index.status(), sourceRevision, reasonCode);
        }
        return rebuild("", "", "");
    }

    public synchronized SkillSearchRebuildResult rebuild(String expectedSourceHash, String actor, String requestId) {
        SkillSearchIndexStatus before = index.status();
        try {
            SkillSearchDocumentSnapshot snapshot = source.snapshot();
            if (expectedSourceHash != null && !expectedSourceHash.isBlank()
                    && !expectedSourceHash.strip().equals(snapshot.sourceHash())) {
                reasonCode = CONFLICT;
                return new SkillSearchRebuildResult(before.revision(), before.documentCount(), snapshot.sourceHash(),
                        Long.toString(snapshot.sourceRevision()), CONFLICT);
            }
            SkillSearchRebuildResult rebuilt = index.rebuild(snapshot.documents(), snapshot.sourceHash());
            sourceRevision = Long.toString(snapshot.sourceRevision());
            reasonCode = "";
            return new SkillSearchRebuildResult(rebuilt.revision(), rebuilt.documentCount(), rebuilt.sourceHash(),
                    sourceRevision, "");
        } catch (RuntimeException exception) {
            reasonCode = REBUILD_FAILED;
            return new SkillSearchRebuildResult(before.revision(), before.documentCount(),
                    before.sourceHash().isEmpty() ? "unavailable" : before.sourceHash(), sourceRevision, REBUILD_FAILED);
        }
    }

    public SkillSearchIndexStatus status() {
        SkillSearchIndexStatus current = index.status();
        String state = reasonCode.isEmpty() ? current.state() : "DEGRADED";
        return new SkillSearchIndexStatus(state, current.revision(), current.documentCount(), current.sourceHash(),
                sourceRevision.isEmpty() ? current.sourceRevision() : sourceRevision, reasonCode);
    }

    private static SkillSearchRebuildResult result(SkillSearchIndexStatus current, String revision, String reason) {
        return new SkillSearchRebuildResult(current.revision(), current.documentCount(),
                current.sourceHash().isEmpty() ? "unavailable" : current.sourceHash(),
                revision.isEmpty() ? current.sourceRevision() : revision, reason);
    }
}
