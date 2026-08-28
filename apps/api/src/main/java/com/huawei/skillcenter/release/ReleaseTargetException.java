package com.huawei.skillcenter.release;

public class ReleaseTargetException extends RuntimeException {
    private final String reasonCode;

    public ReleaseTargetException(String reasonCode) {
        super("Release target execution failed");
        this.reasonCode = reasonCode == null || reasonCode.isBlank() ? "RELEASE_TARGET_FAILED" : reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
