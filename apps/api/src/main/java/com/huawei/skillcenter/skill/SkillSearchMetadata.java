package com.huawei.skillcenter.skill;

import java.util.List;

public record SkillSearchMetadata(double score, List<String> matchedFields) {
    public SkillSearchMetadata {
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
        matchedFields = List.copyOf(matchedFields);
    }
}
