package com.huawei.skillcenter.personal;

import com.huawei.skillcenter.skill.SkillMetrics;

public record PersonalSkillView(String id, String name, String version, String status,
                                String description, String team, String owner, String icon,
                                SkillMetrics metrics, boolean withdrawn) {
}
