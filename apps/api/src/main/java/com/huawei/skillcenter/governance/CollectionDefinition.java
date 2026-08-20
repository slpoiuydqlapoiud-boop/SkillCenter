package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.util.List;

public record CollectionDefinition(String collectionId, String name, String description, String ownerTeamId,
                                   String visibility, List<String> skillIds, int sortOrder, String status,
                                   String updatedBy, Instant updatedAt) {
    public CollectionDefinition {
        skillIds = List.copyOf(skillIds == null ? List.of() : skillIds);
    }
}
