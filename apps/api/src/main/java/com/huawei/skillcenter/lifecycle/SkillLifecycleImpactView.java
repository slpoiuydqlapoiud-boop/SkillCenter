package com.huawei.skillcenter.lifecycle;

import java.util.List;

public record SkillLifecycleImpactView(
        String rootSkillId,
        String rootVersion,
        boolean truncated,
        List<Node> nodes
) {
    public SkillLifecycleImpactView {
        rootSkillId = safe(rootSkillId);
        rootVersion = safe(rootVersion);
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
    }

    public record Node(
            String relationId,
            String skillId,
            String version,
            String relationType,
            String relationStatus,
            int depth,
            boolean productionPromoted,
            List<SkillLifecycleProjectionView.ReleaseView> releases
    ) {
        public Node {
            relationId = safe(relationId);
            skillId = safe(skillId);
            version = safe(version);
            relationType = safe(relationType);
            relationStatus = safe(relationStatus);
            releases = List.copyOf(releases == null ? List.of() : releases);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
