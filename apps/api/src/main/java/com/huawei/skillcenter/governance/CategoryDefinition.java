package com.huawei.skillcenter.governance;

import java.time.Instant;

public record CategoryDefinition(String code, String displayName, String description, int sortOrder,
                                 String status, String updatedBy, Instant updatedAt) {
}
