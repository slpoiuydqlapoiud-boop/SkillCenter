package com.huawei.skillcenter.search;

import java.time.Instant;

/** Metadata-only external search connectivity result. */
public record SkillSearchProbeResult(String backend, String status, String reasonCode,
                                     Integer httpStatus, long latencyMs, Instant checkedAt) {
    public SkillSearchProbeResult {
        backend = SkillSearchDocument.boundedRequired(backend, "backend", 32);
        status = SkillSearchDocument.boundedRequired(status, "status", 32);
        reasonCode = SkillSearchDocument.bounded(reasonCode, "reasonCode", 128);
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be a valid HTTP status");
        }
        if (latencyMs < 0 || checkedAt == null) throw new IllegalArgumentException("probe metadata is invalid");
    }
}
