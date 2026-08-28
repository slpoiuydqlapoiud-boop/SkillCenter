package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;

import java.util.List;

public record SkillQualityDetail(
        String skillId,
        String version,
        QualitySnapshot latestSnapshot,
        RuntimeOperationsSnapshot runtime,
        List<String> availableVersions,
        List<QualitySnapshot> snapshotHistory
) {
    public SkillQualityDetail(String skillId, String version, QualitySnapshot latestSnapshot,
                              RuntimeOperationsSnapshot runtime, List<String> availableVersions) {
        this(skillId, version, latestSnapshot, runtime, availableVersions, List.of());
    }

    public SkillQualityDetail {
        availableVersions = List.copyOf(availableVersions == null ? List.of() : availableVersions);
        snapshotHistory = List.copyOf(snapshotHistory == null ? List.of() : snapshotHistory);
    }
}
