package com.huawei.skillcenter.governance;

import java.util.List;

public record TeamMutation(String teamId, String name, String description, String ownerUserId,
                           List<String> memberUserIds) {
    public TeamMutation {
        memberUserIds = List.copyOf(memberUserIds == null ? List.of() : memberUserIds);
    }
}
