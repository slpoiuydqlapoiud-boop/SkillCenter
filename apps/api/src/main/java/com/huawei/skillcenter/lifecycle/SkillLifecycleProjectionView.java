package com.huawei.skillcenter.lifecycle;

import java.util.List;

public record SkillLifecycleProjectionView(
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
        String ownerTeamId,
        List<ReleaseView> releases
) {
    public SkillLifecycleProjectionView {
        skillId = safe(skillId);
        version = safe(version);
        packageId = safe(packageId);
        status = safe(status);
        latestVersion = safe(latestVersion);
        latestStatus = safe(latestStatus);
        visibility = safe(visibility);
        ownerTeamId = safe(ownerTeamId);
        releases = List.copyOf(releases == null ? List.of() : releases);
    }

    public record ReleaseView(String releaseId, String environment, String status, String gateOutcome) {
        public ReleaseView {
            releaseId = safe(releaseId);
            environment = safe(environment);
            status = safe(status);
            gateOutcome = safe(gateOutcome);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
