package com.huawei.skillcenter.quality;

/** Status-only result from a provider probe; response bodies are intentionally excluded. */
public record ProviderProbeTransportResult(Integer httpStatus, long latencyMs, String failureReason) {
    public ProviderProbeTransportResult {
        latencyMs = Math.max(0, latencyMs);
        failureReason = failureReason == null ? "" : failureReason.trim();
    }

    public static ProviderProbeTransportResult http(int status, long latencyMs) {
        return new ProviderProbeTransportResult(status, latencyMs, "");
    }

    public static ProviderProbeTransportResult failure(String reason, long latencyMs) {
        return new ProviderProbeTransportResult(null, latencyMs, reason);
    }
}
