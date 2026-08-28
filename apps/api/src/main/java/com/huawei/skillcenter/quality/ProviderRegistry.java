package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProviderRegistry {
    private final List<ProviderDescriptor> providers;

    public ProviderRegistry(SkillRunner runner, EvaluationProvider evaluator, ObservabilityProvider observability) {
        providers = List.of(
                descriptor(runner, "runner"),
                descriptor(evaluator, "evaluation"),
                descriptor(observability, "observability"));
    }

    public List<ProviderDescriptor> list() {
        return providers;
    }

    private ProviderDescriptor descriptor(SkillRunner provider, String kind) {
        ProviderHealth health = provider.health();
        return new ProviderDescriptor(provider.providerId(), kind, provider.providerVersion(), health.status(),
                provider.capabilities(), health.reason());
    }

    private ProviderDescriptor descriptor(EvaluationProvider provider, String kind) {
        ProviderHealth health = provider.health();
        return new ProviderDescriptor(provider.providerId(), kind, provider.providerVersion(), health.status(),
                provider.capabilities(), health.reason());
    }

    private ProviderDescriptor descriptor(ObservabilityProvider provider, String kind) {
        ProviderHealth health = provider.health();
        return new ProviderDescriptor(provider.providerId(), kind, provider.providerVersion(), health.status(),
                provider.capabilities(), health.reason());
    }
}
