package com.huawei.skillcenter.operations;

import java.time.Instant;

public record RuntimeOperationsQuery(
        RuntimeOperationsWindow window,
        String skillId,
        String version,
        String teamId,
        String dataSource,
        Instant now,
        String runtimeId,
        String mcpServerId,
        String llmProviderId
) {
    public RuntimeOperationsQuery {
        window = window == null ? RuntimeOperationsWindow.TWENTY_FOUR_HOURS : window;
        skillId = normalize(skillId);
        version = normalize(version);
        teamId = normalize(teamId);
        dataSource = normalize(dataSource);
        runtimeId = normalizeEnvironment(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironment(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironment(llmProviderId, "llmProviderId");
        if (dataSource != null && !java.util.Set.of("mock", "production", "all").contains(dataSource)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
    }

    public RuntimeOperationsQuery(RuntimeOperationsWindow window, String skillId, String version,
                                  String teamId, String dataSource) {
        this(window, skillId, version, teamId, dataSource, null, null, null, null);
    }

    public RuntimeOperationsQuery(RuntimeOperationsWindow window, String skillId, String version,
                                  String teamId, String dataSource, Instant now) {
        this(window, skillId, version, teamId, dataSource, now, null, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeEnvironment(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }
}
