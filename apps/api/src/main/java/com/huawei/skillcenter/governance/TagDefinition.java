package com.huawei.skillcenter.governance;

import java.time.Instant;

public record TagDefinition(String code, String displayName, String description, int sortOrder,
                            String status, String updatedBy, Instant updatedAt) {
}
