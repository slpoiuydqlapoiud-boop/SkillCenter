package com.huawei.skillcenter.quality;

import java.util.List;

public record ProviderDescriptor(
        String id,
        String kind,
        String version,
        String status,
        List<String> capabilities,
        String healthReason
) {
    public ProviderDescriptor(String id, String kind, String version, String status) {
        this(id, kind, version, status, List.of(), "");
    }

    public ProviderDescriptor {
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        healthReason = healthReason == null ? "" : healthReason;
    }
}
