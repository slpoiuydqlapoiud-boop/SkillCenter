package com.huawei.skillcenter.quality;

import java.util.List;

/**
 * Non-secret, read-only readiness summary for the configured Provider graph.
 * It deliberately reports contract state only and never performs a network call.
 * The stable reason code is retained for automation; reasonDescription is safe for operator-facing UI.
 */
public record ProviderReadinessSummary(
        String status,
        int activeProviderCount,
        int healthyProviderCount,
        int contractOnlyProviderCount,
        int notConfiguredProviderCount,
        String reason,
        String reasonDescription,
        List<String> contractOnlyProviderIds,
        List<String> notConfiguredProviderIds
) {
    public ProviderReadinessSummary(String status,
                                    int activeProviderCount,
                                    int healthyProviderCount,
                                    int contractOnlyProviderCount,
                                    int notConfiguredProviderCount,
                                    String reason) {
        this(status, activeProviderCount, healthyProviderCount, contractOnlyProviderCount,
                notConfiguredProviderCount, reason, describe(reason), List.of(), List.of());
    }

    public ProviderReadinessSummary {
        reasonDescription = reasonDescription == null || reasonDescription.isBlank()
                ? describe(reason) : reasonDescription;
        contractOnlyProviderIds = stableIds(contractOnlyProviderIds);
        notConfiguredProviderIds = stableIds(notConfiguredProviderIds);
    }

    private static List<String> stableIds(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private static String describe(String reason) {
        return switch (reason == null ? "" : reason) {
            case "EXTERNAL_PROVIDERS_CONTRACT_ONLY" -> "外部 Provider 尚未启用真实适配器";
            case "ACTIVE_PROVIDER_NOT_CONFIGURED" -> "存在未配置的活动 Provider";
            case "ALL_PROVIDERS_READY" -> "所有 Provider 已就绪";
            default -> "请检查 Provider 配置";
        };
    }
}
