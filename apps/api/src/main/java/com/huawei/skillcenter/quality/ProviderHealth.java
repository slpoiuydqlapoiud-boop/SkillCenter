package com.huawei.skillcenter.quality;

public record ProviderHealth(String status, String reason) {
    public static ProviderHealth up() {
        return new ProviderHealth("UP", "");
    }

    public static ProviderHealth notConfigured() {
        return new ProviderHealth("NOT_CONFIGURED", "EXTERNAL_ADAPTER_NOT_CONFIGURED");
    }

    public static ProviderHealth contractOnly() {
        return new ProviderHealth("CONTRACT_ONLY", "EXTERNAL_ADAPTER_NOT_ENABLED");
    }
}
