package com.huawei.skillcenter.skill;

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
        SkillMetrics metrics
) {
    public static SkillSummary from(SkillRecord skill) {
        return new SkillSummary(skill.id(), skill.name(), skill.version(), skill.description(), skill.category(),
                skill.tags(), skill.risk(), skill.riskTone(), skill.team(), skill.owner(), skill.icon(),
                skill.iconTone(), skill.status(), skill.lastUpdated(), skill.metrics());
    }
}
