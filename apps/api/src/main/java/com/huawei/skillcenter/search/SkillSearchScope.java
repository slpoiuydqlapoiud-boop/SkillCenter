package com.huawei.skillcenter.search;

import java.util.Locale;
import java.util.Set;

/** Bounded visibility metadata supplied by a later integration boundary. */
public record SkillSearchScope(String skillId, String visibility, String ownerTeamId) {
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "TEAM", "RESTRICTED");

    public SkillSearchScope {
        skillId = SkillSearchDocument.boundedRequired(skillId, "skillId", 128);
        visibility = SkillSearchDocument.boundedRequired(visibility, "visibility", 32).toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(visibility)) {
            throw new IllegalArgumentException("unsupported visibility");
        }
        ownerTeamId = SkillSearchDocument.bounded(ownerTeamId, "ownerTeamId", 128);
    }
}
