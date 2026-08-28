package com.huawei.skillcenter.lifecycle;

import java.util.Locale;
import java.util.Set;

/** Metadata-only security finding retained by the lifecycle projection. */
public record SkillLifecycleSecurityFindingRow(String code, String path, String severity) {
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH");

    public SkillLifecycleSecurityFindingRow {
        code = SkillLifecycleProjectionInput.requireCode(code, "security finding code");
        path = requirePath(path);
        String normalizedSeverity = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(normalizedSeverity)) {
            throw new IllegalArgumentException("security finding severity must be a known level");
        }
        severity = normalizedSeverity;
    }

    private static String requirePath(String value) {
        String normalized = SkillLifecycleProjectionInput.requireText(value, "security finding path", 512);
        if (normalized.indexOf('\u0000') >= 0 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("security finding path contains control characters");
        }
        return normalized;
    }
}
