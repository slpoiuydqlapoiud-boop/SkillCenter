package com.huawei.skillcenter.release;

public class ReleaseAdmissionException extends RuntimeException {
    private final String reasonCode;

    public ReleaseAdmissionException(String reasonCode, String message) {
        super(message);
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode is required");
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
