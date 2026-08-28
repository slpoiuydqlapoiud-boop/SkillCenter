package com.huawei.skillcenter.governance;

/** Reads all lifecycle references used to protect retained evidence. */
@FunctionalInterface
public interface RetentionEvidenceReferenceIndex {
    RetentionProtectionSnapshot snapshot();

    static RetentionEvidenceReferenceIndex empty() {
        return RetentionProtectionSnapshot::empty;
    }
}
