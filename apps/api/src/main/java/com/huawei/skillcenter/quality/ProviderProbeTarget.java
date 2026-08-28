package com.huawei.skillcenter.quality;

/** Safe, non-secret configuration used by the provider connectivity probe. */
public record ProviderProbeTarget(String providerId, String kind, String mode, ProviderAdapterConfig config) {
    public ProviderProbeTarget {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        mode = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        config = config == null ? ProviderAdapterConfig.disabled() : config;
    }
}
