package com.huawei.skillcenter.release;

public record ReleaseAdmissionDecision(
        boolean allowed,
        ReleaseAdmissionMode mode,
        String reasonCode,
        String releaseId,
        boolean legacyCompatible
) {
    public ReleaseAdmissionDecision {
        if (mode == null) throw new IllegalArgumentException("mode is required");
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode is required");
        releaseId = releaseId == null ? "" : releaseId.trim();
        if (!allowed && legacyCompatible) throw new IllegalArgumentException("denied decision cannot be legacy compatible");
    }

    public static ReleaseAdmissionDecision legacy(ReleaseAdmissionMode mode) {
        return new ReleaseAdmissionDecision(true, mode, "LEGACY_COMPATIBLE", "", true);
    }

    public static ReleaseAdmissionDecision promoted(ReleaseAdmissionMode mode, ReleaseRecord record) {
        return new ReleaseAdmissionDecision(true, mode, "RELEASE_PROMOTED", record.releaseId(), false);
    }

    public static ReleaseAdmissionDecision denied(ReleaseAdmissionMode mode, String reasonCode) {
        return new ReleaseAdmissionDecision(false, mode, reasonCode, "", false);
    }
}
