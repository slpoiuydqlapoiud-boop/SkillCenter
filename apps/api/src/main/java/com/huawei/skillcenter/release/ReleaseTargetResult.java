package com.huawei.skillcenter.release;

import java.time.Instant;
import java.util.Locale;

public record ReleaseTargetResult(String status, String reasonCode, String externalReference,
                                  long durationMs, Instant completedAt) {
    public ReleaseTargetResult {
        status = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        reasonCode = reasonCode == null ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
        externalReference = externalReference == null ? "" : externalReference.trim();
        if (!status.equals("SUCCEEDED") && !status.equals("FAILED")) {
            throw new IllegalArgumentException("target result status must be SUCCEEDED or FAILED");
        }
        if (durationMs < 0 || durationMs > 86_400_000) throw new IllegalArgumentException("durationMs is out of range");
        completedAt = completedAt == null ? Instant.now() : completedAt;
        if ("SUCCEEDED".equals(status) && externalReference.isBlank()) {
            throw new IllegalArgumentException("successful target result requires reference");
        }
        if ("FAILED".equals(status) && reasonCode.isBlank()) {
            throw new IllegalArgumentException("failed target result requires reasonCode");
        }
    }

    public static ReleaseTargetResult success(String reference) {
        return new ReleaseTargetResult("SUCCEEDED", "", reference, 0, Instant.now());
    }

    public static ReleaseTargetResult failure(String reasonCode) {
        return new ReleaseTargetResult("FAILED", reasonCode, "", 0, Instant.now());
    }

    public boolean success() {
        return "SUCCEEDED".equals(status);
    }

    public String reference() {
        return externalReference;
    }
}
