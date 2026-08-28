package com.huawei.skillcenter.lifecycle;

import java.util.List;

/**
 * Administrative projection response. Deliberately excludes internal ownership metadata.
 */
public record SkillLifecycleProjectionResponse(
        String skillId,
        String version,
        String packageId,
        String status,
        String latestVersion,
        String latestStatus,
        int versionCount,
        int publishedVersionCount,
        int activeReleaseCount,
        String visibility,
        List<SkillLifecycleProjectionView.ReleaseView> releases
) {
    public static SkillLifecycleProjectionResponse from(SkillLifecycleProjectionView view) {
        return new SkillLifecycleProjectionResponse(
                view.skillId(),
                view.version(),
                view.packageId(),
                view.status(),
                view.latestVersion(),
                view.latestStatus(),
                view.versionCount(),
                view.publishedVersionCount(),
                view.activeReleaseCount(),
                view.visibility(),
                view.releases());
    }
}
