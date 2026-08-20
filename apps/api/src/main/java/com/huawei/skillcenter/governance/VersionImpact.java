package com.huawei.skillcenter.governance;

import java.util.Map;

public record VersionImpact(
        String skillId,
        String version,
        long installationCount,
        long activeInstallationCount,
        long userCount,
        long teamCount,
        Map<String, Long> clientTypes,
        long invocationCount,
        long activeInvocationUsers,
        String replacementVersion
) {
    public VersionImpact {
        clientTypes = Map.copyOf(clientTypes == null ? Map.of() : clientTypes);
    }
}
