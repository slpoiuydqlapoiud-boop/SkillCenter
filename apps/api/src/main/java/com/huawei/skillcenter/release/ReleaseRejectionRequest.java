package com.huawei.skillcenter.release;

public record ReleaseRejectionRequest(String reason) {
    public ReleaseRejectionRequest {
        if (reason == null || reason.isBlank() || reason.trim().length() > 512) {
            throw new IllegalArgumentException("reason must be between 1 and 512 characters");
        }
        reason = reason.trim();
    }
}
