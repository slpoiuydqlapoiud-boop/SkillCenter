package com.huawei.skillcenter.governance;

/** Raised when a governance aggregate write uses a stale revision. */
public class GovernanceStateConflictException extends RuntimeException {
    public GovernanceStateConflictException(String message) {
        super(message);
    }
}
