package com.huawei.skillcenter.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public record SkillSearchRefreshEvent(String skillId, long sourceRevision, String reasonCode) {
    public SkillSearchRefreshEvent {
        skillId = SkillSearchDocument.boundedRequired(skillId, "skillId", 128);
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must be non-negative");
        }
        reasonCode = SkillSearchDocument.boundedRequired(reasonCode, "reasonCode", 128);
    }

    /** Stable, metadata-only idempotency key for at-least-once delivery. */
    public String eventKey() {
        return skillId + "|" + sourceRevision + "|" + reasonCode;
    }

    /** Stable metadata identity for a source fact when the source has no persisted numeric revision. */
    public static long stableSourceRevision(String... identityParts) {
        if (identityParts == null || identityParts.length == 0
                || java.util.Arrays.stream(identityParts).anyMatch(part -> part == null || part.isBlank())) {
            throw new IllegalArgumentException("identity parts are required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", identityParts).getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                value = (value << 8) | (digest[index] & 0xffL);
            }
            value &= Long.MAX_VALUE;
            return value == 0L ? 1L : value;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
