package com.huawei.skillcenter.quality;

import java.util.List;

public record ExternalProviderDescriptor(
        String id,
        String kind,
        String version,
        String status,
        List<String> capabilities,
        String configReference
) {
    public ExternalProviderDescriptor {
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
    }
}
