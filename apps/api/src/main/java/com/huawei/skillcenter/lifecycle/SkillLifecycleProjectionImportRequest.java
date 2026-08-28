package com.huawei.skillcenter.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Map;

public record SkillLifecycleProjectionImportRequest(
        String sourceSha256
) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SkillLifecycleProjectionImportRequest fromJson(Map<String, Object> body) {
        if (body == null || body.size() != 1 || !body.containsKey("sourceSha256")) {
            throw new IllegalArgumentException("Request body does not match the allowed event schema");
        }
        Object value = body.get("sourceSha256");
        if (!(value instanceof String hash)) {
            throw new IllegalArgumentException("Request body does not match the allowed event schema");
        }
        return new SkillLifecycleProjectionImportRequest(hash);
    }

    public SkillLifecycleProjectionImportRequest {
        if (sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Request body does not match the allowed event schema");
        }
    }
}
