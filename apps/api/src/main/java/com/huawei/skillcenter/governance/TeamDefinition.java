package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.util.List;

public record TeamDefinition(String teamId, String name, String description, String ownerUserId,
                             List<String> memberUserIds, String status, Instant createdAt, Instant updatedAt) {
    public TeamDefinition {
        memberUserIds = List.copyOf(memberUserIds == null ? List.of() : memberUserIds);
    }
}
