package com.huawei.skillcenter.quality;

import java.util.List;

/** Reserved contract inventory; actual active status comes from the selected Provider adapter. */
public final class ExternalProviderCatalog {
    private ExternalProviderCatalog() {
    }

    public static List<ExternalProviderDescriptor> list() {
        return List.of(
                new ExternalProviderDescriptor("openclaw-runner", "runner", "contract-v1",
                        "CONTRACT_ONLY", List.of("execute", "timeout", "cancel"), "secret://<provider>"),
                new ExternalProviderDescriptor("deepeval-evaluation", "evaluation", "contract-v1",
                        "CONTRACT_ONLY", List.of("evaluate", "score", "compare"), "secret://<provider>"),
                new ExternalProviderDescriptor("langfuse-observability", "observability", "contract-v1",
                        "CONTRACT_ONLY", List.of("trace-reference", "metrics", "errors"), "secret://<provider>"));
    }
}
