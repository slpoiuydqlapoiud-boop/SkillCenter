package com.huawei.skillcenter.execution;

/**
 * Immutable execution-environment context captured with quality or runtime evidence.
 *
 * <p>A snapshot is intentionally smaller than the catalog entity: evidence needs the
 * identity, version, revision and state that make a run reproducible, not credentials
 * or mutable configuration references.</p>
 */
public record ExecutionEnvironmentSnapshot(
        ExecutionEnvironmentKind kind,
        String environmentId,
        String version,
        int revision,
        ExecutionEnvironmentStatus status
) {
    private static final String IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    public ExecutionEnvironmentSnapshot {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        environmentId = normalizeOptionalIdentifier(environmentId, "environmentId");
        version = normalizeOptionalText(version, "version", 128);
        if (environmentId.isBlank()) {
            if (!version.isBlank() || revision != 0 || status != null) {
                throw new IllegalArgumentException("empty execution environment snapshot must not contain context");
            }
        } else if (!(version.isBlank() && revision == 0 && status == null)) {
            if (version.isBlank()) throw new IllegalArgumentException("version is required for a complete snapshot");
            if (revision < 1) throw new IllegalArgumentException("revision must be at least 1");
            if (status == null) throw new IllegalArgumentException("status is required for a complete snapshot");
        }
    }

    public static ExecutionEnvironmentSnapshot empty(ExecutionEnvironmentKind kind) {
        return new ExecutionEnvironmentSnapshot(kind, "", "", 0, null);
    }

    public static ExecutionEnvironmentSnapshot legacy(ExecutionEnvironmentKind kind, String environmentId) {
        return new ExecutionEnvironmentSnapshot(kind, environmentId, "", 0, null);
    }

    public static ExecutionEnvironmentSnapshot from(ExecutionEnvironment environment) {
        if (environment == null) throw new IllegalArgumentException("environment is required");
        return new ExecutionEnvironmentSnapshot(environment.kind(), environment.environmentId(),
                environment.version(), environment.revision(), environment.status());
    }

    public static ExecutionEnvironmentSnapshot normalize(ExecutionEnvironmentKind kind, String environmentId,
                                                          ExecutionEnvironmentSnapshot snapshot) {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        String normalizedId = normalizeOptionalIdentifier(environmentId, "environmentId");
        if (snapshot == null) {
            return normalizedId.isBlank() ? empty(kind) : legacy(kind, normalizedId);
        }
        if (snapshot.kind() != kind) throw new IllegalArgumentException("execution environment kind does not match");
        if (!normalizedId.equals(snapshot.environmentId())) {
            throw new IllegalArgumentException("execution environment identifier does not match snapshot");
        }
        return snapshot;
    }

    public boolean complete() {
        return !environmentId.isBlank() && !version.isBlank() && revision >= 1 && status != null;
    }

    private static String normalizeOptionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !normalized.matches(IDENTIFIER)) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeOptionalText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " exceeds maximum length");
        return normalized;
    }
}
