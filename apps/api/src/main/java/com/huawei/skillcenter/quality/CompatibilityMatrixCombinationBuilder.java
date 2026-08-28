package com.huawei.skillcenter.quality;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class CompatibilityMatrixCombinationBuilder {
    public static final int MAX_DIMENSION_SIZE = 10;
    public static final int MAX_COMBINATIONS = 100;

    private CompatibilityMatrixCombinationBuilder() {
    }

    public static List<CompatibilityMatrixCombination> build(List<String> runtimeIds,
                                                              List<String> mcpServerIds,
                                                              List<String> llmProviderIds) {
        List<String> runtimes = normalizeDimension(runtimeIds, "runtimeIds");
        List<String> mcps = normalizeDimension(mcpServerIds, "mcpServerIds");
        List<String> llms = normalizeDimension(llmProviderIds, "llmProviderIds");
        long combinations = (long) runtimes.size() * mcps.size() * llms.size();
        if (combinations > MAX_COMBINATIONS) {
            throw new IllegalArgumentException("compatibility matrix cannot contain more than 100 combinations");
        }
        List<CompatibilityMatrixCombination> result = runtimes.stream()
                .flatMap(runtime -> mcps.stream().flatMap(mcp -> llms.stream()
                        .map(llm -> new CompatibilityMatrixCombination(runtime, mcp, llm))))
                .sorted(Comparator.comparing(CompatibilityMatrixCombination::runtimeId)
                        .thenComparing(CompatibilityMatrixCombination::mcpServerId)
                        .thenComparing(CompatibilityMatrixCombination::llmProviderId))
                .toList();
        if (result.stream().allMatch(item -> item.runtimeId().isBlank()
                && item.mcpServerId().isBlank() && item.llmProviderId().isBlank())) {
            throw new IllegalArgumentException("compatibility matrix must select at least one environment");
        }
        return result;
    }

    static List<String> normalizeDimension(List<String> values, String field) {
        if (values == null || values.isEmpty()) return List.of("");
        if (values.size() > MAX_DIMENSION_SIZE) {
            throw new IllegalArgumentException(field + " cannot contain more than 10 identifiers");
        }
        List<String> normalized = values.stream().map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank()).distinct().sorted().toList();
        if (normalized.isEmpty()) return List.of("");
        if (normalized.stream().anyMatch(value -> !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            throw new IllegalArgumentException(field + " contains an invalid identifier");
        }
        return normalized;
    }
}
