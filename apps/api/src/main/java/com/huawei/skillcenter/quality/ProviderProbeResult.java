package com.huawei.skillcenter.quality;

import java.time.Instant;

/** Operator-safe connectivity signal. It contains no endpoint, credential, or response body. */
public record ProviderProbeResult(
        String providerId,
        String kind,
        String status,
        String reason,
        Integer httpStatus,
        long latencyMs,
        Instant checkedAt
) {
}
