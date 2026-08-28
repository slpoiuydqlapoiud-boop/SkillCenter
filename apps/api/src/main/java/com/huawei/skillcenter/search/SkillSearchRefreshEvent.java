package com.huawei.skillcenter.search;

public record SkillSearchRefreshEvent(String skillId, long sourceRevision, String reasonCode) {
    public SkillSearchRefreshEvent {
        skillId = SkillSearchDocument.boundedRequired(skillId, "skillId", 128);
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must be non-negative");
        }
        reasonCode = SkillSearchDocument.boundedRequired(reasonCode, "reasonCode", 128);
    }
}
