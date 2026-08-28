package com.huawei.skillcenter.relationship;

public record SkillRelationQuery(
        String sourceSkillId,
        String sourceVersion,
        String targetSkillId,
        String targetVersion,
        SkillRelationStatus status,
        Integer maxDepth,
        Integer maxNodes
) {
    public static final int DEFAULT_MAX_DEPTH = 5;
    public static final int DEFAULT_MAX_NODES = 100;
    public static final int MAX_DEPTH = 10;
    public static final int MAX_NODES = 500;

    public SkillRelationQuery {
        sourceSkillId = normalize(sourceSkillId);
        sourceVersion = normalize(sourceVersion);
        targetSkillId = normalize(targetSkillId);
        targetVersion = normalize(targetVersion);
        maxDepth = maxDepth == null ? DEFAULT_MAX_DEPTH : maxDepth;
        maxNodes = maxNodes == null ? DEFAULT_MAX_NODES : maxNodes;
    }

    public static SkillRelationQuery defaults() {
        return new SkillRelationQuery(null, null, null, null, null, DEFAULT_MAX_DEPTH, DEFAULT_MAX_NODES);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
