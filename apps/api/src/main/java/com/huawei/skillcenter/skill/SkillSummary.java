package com.huawei.skillcenter.skill;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record SkillSummary(
        String id,
        String name,
        String version,
        String description,
        String category,
        List<String> tags,
        String risk,
        String riskTone,
        String team,
        String owner,
        String icon,
        String iconTone,
        String status,
        String lastUpdated,
        SkillMetrics metrics,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) SkillSearchMetadata search
) {
    public SkillSummary(String id, String name, String version, String description, String category, List<String> tags,
                        String risk, String riskTone, String team, String owner, String icon, String iconTone,
                        String status, String lastUpdated, SkillMetrics metrics) {
        this(id, name, version, description, category, tags, risk, riskTone, team, owner, icon, iconTone, status,
                lastUpdated, metrics, null);
    }

    public static SkillSummary from(SkillRecord skill) {
        return new SkillSummary(skill.id(), skill.name(), skill.version(), skill.description(), skill.category(),
                skill.tags(), skill.risk(), skill.riskTone(), skill.team(), skill.owner(), skill.icon(),
                skill.iconTone(), skill.status(), skill.lastUpdated(), skill.metrics());
    }
}
