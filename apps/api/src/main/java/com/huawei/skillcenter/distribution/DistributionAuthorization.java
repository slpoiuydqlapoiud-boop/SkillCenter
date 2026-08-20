package com.huawei.skillcenter.distribution;

import java.time.Instant;

public record DistributionAuthorization(
        String tokenId,
        String tokenDigest,
        String skillId,
        String version,
        String installationId,
        String requestedBy,
        String clientType,
        String clientVersion,
        String method,
        Instant issuedAt,
        Instant expiresAt,
        Instant consumedAt,
        Instant revokedAt,
        String revokeReason
) {
}
