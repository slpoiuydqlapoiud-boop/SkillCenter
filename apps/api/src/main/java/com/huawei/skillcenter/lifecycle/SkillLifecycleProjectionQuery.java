package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.release.ReleaseEnvironment;

public record SkillLifecycleProjectionQuery(
        String skillId,
        String version,
        String status,
        ReleaseEnvironment environment,
        int page,
        int pageSize
) {
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    public SkillLifecycleProjectionQuery {
        skillId = normalize(skillId);
        version = normalize(version);
        status = normalizeCode(status);
        page = page < 1 ? DEFAULT_PAGE : page;
        pageSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public static SkillLifecycleProjectionQuery defaults() {
        return new SkillLifecycleProjectionQuery("", "", "", null, DEFAULT_PAGE, DEFAULT_PAGE_SIZE);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeCode(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
