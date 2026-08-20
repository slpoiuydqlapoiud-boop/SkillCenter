package com.huawei.skillcenter.governance;

import java.util.List;

public record CollectionMutation(String collectionId, String name, String description, String ownerTeamId,
                                 String visibility, List<String> skillIds, int sortOrder) {
    public CollectionMutation {
        skillIds = List.copyOf(skillIds == null ? List.of() : skillIds);
    }
}
