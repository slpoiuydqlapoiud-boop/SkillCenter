package com.huawei.skillcenter.search;

import java.util.List;

public interface SkillSearchIndex {
    SkillSearchIndexStatus status();

    SkillSearchRebuildResult rebuild(List<SkillSearchDocument> documents, String sourceHash);

    void invalidate(String reason);

    List<SkillSearchHit> search(SkillSearchQuery query);
}
