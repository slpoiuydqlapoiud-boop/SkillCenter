package com.huawei.skillcenter.governance;

public enum ExportJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    EXPIRED;

    public boolean canTransitionTo(ExportJobStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case QUEUED -> next == RUNNING || next == FAILED;
            case RUNNING -> next == COMPLETED || next == FAILED;
            case COMPLETED -> next == EXPIRED;
            case FAILED -> next == QUEUED;
            case EXPIRED -> false;
        };
    }
}
