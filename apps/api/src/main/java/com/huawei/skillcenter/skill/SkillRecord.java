package com.huawei.skillcenter.skill;

import java.util.List;

public record SkillRecord(
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
        String publishedAt,
        String language,
        String supportedLanguage,
        String permissionSummary,
        List<String> capabilities,
        List<String> suitableFor,
        List<String> unsuitableFor,
        String value,
        String exampleInput,
        String exampleOutput,
        List<String> collection,
        SkillMetrics metrics
) {
}
