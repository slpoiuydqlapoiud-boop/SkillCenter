package com.huawei.skillcenter.release;

import java.util.EnumSet;
import java.util.Set;

public enum ReleaseStatus {
    REQUESTED,
    APPROVED,
    PROMOTING,
    PROMOTED,
    REJECTED,
    ROLLBACK_REVIEW,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED;

    public boolean terminal() {
        return EnumSet.of(REJECTED, PROMOTED, ROLLED_BACK, FAILED).contains(this);
    }

    public boolean canTransitionTo(ReleaseStatus target) {
        if (target == null) return false;
        Set<ReleaseStatus> allowed = switch (this) {
            case REQUESTED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(PROMOTING, REJECTED);
            case PROMOTING -> EnumSet.of(PROMOTED, FAILED);
            case PROMOTED -> EnumSet.of(ROLLBACK_REVIEW);
            case ROLLBACK_REVIEW -> EnumSet.of(ROLLING_BACK);
            case ROLLING_BACK -> EnumSet.of(ROLLED_BACK, FAILED);
            case REJECTED, ROLLED_BACK, FAILED -> EnumSet.noneOf(ReleaseStatus.class);
        };
        return allowed.contains(target);
    }
}
