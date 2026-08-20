package com.huawei.skillcenter.governance;

import java.util.List;

public record GovernanceConfiguration(
        List<TeamDefinition> teams,
        List<RoleBinding> roleBindings,
        List<CategoryDefinition> categories,
        List<TagDefinition> tags,
        List<CollectionDefinition> collections,
        PlatformPolicy platformPolicy
) {
    public GovernanceConfiguration {
        teams = List.copyOf(teams == null ? List.of() : teams);
        roleBindings = List.copyOf(roleBindings == null ? List.of() : roleBindings);
        categories = List.copyOf(categories == null ? List.of() : categories);
        tags = List.copyOf(tags == null ? List.of() : tags);
        collections = List.copyOf(collections == null ? List.of() : collections);
        platformPolicy = platformPolicy == null ? PlatformPolicy.defaults() : platformPolicy;
    }

    public static GovernanceConfiguration empty() {
        return new GovernanceConfiguration(List.of(), List.of(), List.of(), List.of(), List.of(), PlatformPolicy.defaults());
    }
}
