package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.util.List;

public record PlatformPolicy(int policyVersion, List<Integer> pageSizeOptions, int maxPageSize,
                             String minimumClientVersion, String defaultCollectionVisibility,
                             String updatedBy, Instant updatedAt) {
    public PlatformPolicy {
        pageSizeOptions = List.copyOf(pageSizeOptions == null || pageSizeOptions.isEmpty()
                ? List.of(12, 24, 48) : pageSizeOptions);
        minimumClientVersion = minimumClientVersion == null || minimumClientVersion.isBlank()
                ? "1.0.0" : minimumClientVersion;
        defaultCollectionVisibility = defaultCollectionVisibility == null || defaultCollectionVisibility.isBlank()
                ? "public" : defaultCollectionVisibility;
    }

    public static PlatformPolicy defaults() {
        return new PlatformPolicy(1, List.of(12, 24, 48), 48, "1.0.0", "public", null, null);
    }
}
