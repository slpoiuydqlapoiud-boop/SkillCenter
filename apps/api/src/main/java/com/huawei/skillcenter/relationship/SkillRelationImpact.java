package com.huawei.skillcenter.relationship;

import java.util.List;

public record SkillRelationImpact(
        String rootSkillId,
        String rootVersion,
        int maxDepth,
        int maxNodes,
        boolean truncated,
        List<SkillRelationImpactNode> nodes
) {
    public SkillRelationImpact {
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
    }
}
