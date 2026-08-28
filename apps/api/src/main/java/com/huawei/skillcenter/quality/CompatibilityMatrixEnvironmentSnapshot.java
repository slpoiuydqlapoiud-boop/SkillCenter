package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentStatus;

import java.util.List;

/** Safe, historical execution-environment metadata captured with a matrix case. */
public record CompatibilityMatrixEnvironmentSnapshot(
        String environmentId,
        String version,
        ExecutionEnvironmentStatus status,
        List<String> capabilities,
        String adapterProviderId
) {
    public CompatibilityMatrixEnvironmentSnapshot {
        environmentId = optionalIdentifier(environmentId, "environmentId");
        version = optionalText(version, "version", 128);
        if (environmentId.isBlank()) {
            if (status != null || !version.isBlank() || (capabilities != null && !capabilities.isEmpty())
                    || (adapterProviderId != null && !adapterProviderId.isBlank())) {
                throw new IllegalArgumentException("empty environment snapshot must not contain metadata");
            }
        } else if (status == null || version.isBlank()) {
            throw new IllegalArgumentException("selected environment snapshot requires version and status");
        }
        capabilities = normalizeCapabilities(capabilities);
        adapterProviderId = optionalIdentifier(adapterProviderId, "adapterProviderId");
    }

    public static CompatibilityMatrixEnvironmentSnapshot empty() {
        return new CompatibilityMatrixEnvironmentSnapshot("", "", null, List.of(), "");
    }

    public static CompatibilityMatrixEnvironmentSnapshot of(String environmentId, String version,
                                                             ExecutionEnvironmentStatus status,
                                                             List<String> capabilities, String adapterProviderId) {
        return new CompatibilityMatrixEnvironmentSnapshot(environmentId, version, status, capabilities, adapterProviderId);
    }

    private static List<String> normalizeCapabilities(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new IllegalArgumentException("capabilities must contain at most 20 items");
        return values.stream().map(value -> optionalText(value, "capability", 64))
                .peek(value -> {
                    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                        throw new IllegalArgumentException("capability must be a bounded identifier");
                    }
                }).distinct().sorted().toList();
    }

    private static String optionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " exceeds maximum length");
        return normalized;
    }
}
