package com.huawei.skillcenter.governance;

public record AuditIntegrityStatus(boolean valid, long checkedEntries, String errorCode) {
    public static AuditIntegrityStatus valid(long checkedEntries) {
        return new AuditIntegrityStatus(true, checkedEntries, null);
    }

    public static AuditIntegrityStatus invalid(long checkedEntries, String errorCode) {
        return new AuditIntegrityStatus(false, checkedEntries, errorCode);
    }
}
