package com.huawei.skillcenter.search;

import java.util.List;
import java.util.Set;

public record SkillSearchHit(String skillId, double score, List<String> matchedFields) {
    private static final Set<String> ALLOWED_FIELDS = Set.of("id", "name", "tags", "description", "team", "category");

    public SkillSearchHit {
        skillId = SkillSearchDocument.boundedRequired(skillId, "skillId", 128);
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
        if (matchedFields == null || matchedFields.size() > 6 || matchedFields.stream().anyMatch(field -> !ALLOWED_FIELDS.contains(field))) {
            throw new IllegalArgumentException("matchedFields contains an unsupported field");
        }
        matchedFields = List.copyOf(matchedFields);
    }
}
