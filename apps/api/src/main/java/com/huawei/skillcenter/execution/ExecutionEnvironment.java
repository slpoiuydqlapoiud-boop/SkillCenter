package com.huawei.skillcenter.execution;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ExecutionEnvironment(
        String environmentId,
        ExecutionEnvironmentKind kind,
        String version,
        ExecutionEnvironmentStatus status,
        List<String> capabilities,
        String adapterProviderId,
        String configReference,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        int revision
) {
    private static final String IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final Set<String> RAW_CREDENTIAL_PREFIXES = Set.of("bearer ", "basic ", "sk-");

    public ExecutionEnvironment {
        environmentId = normalizeIdentifier(environmentId, "environmentId");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        version = normalizeText(version, "version", 128);
        if (status == null) throw new IllegalArgumentException("status is required");
        capabilities = normalizeCapabilities(capabilities);
        adapterProviderId = normalizeOptionalIdentifier(adapterProviderId, "adapterProviderId");
        configReference = normalizeConfigReference(configReference);
        createdBy = normalizeIdentifier(createdBy, "createdBy");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedBy = normalizeIdentifier(updatedBy, "updatedBy");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (revision == 0) revision = 1;
        if (revision < 1) throw new IllegalArgumentException("revision must be at least 1");
    }

    public String businessKey() {
        return kind.name() + "/" + environmentId;
    }

    public ExecutionEnvironment(String environmentId, ExecutionEnvironmentKind kind, String version,
                                 ExecutionEnvironmentStatus status, List<String> capabilities,
                                 String adapterProviderId, String configReference) {
        this(environmentId, kind, version, status, capabilities, adapterProviderId, configReference,
                "system", Instant.now(), "system", Instant.now(), 1);
    }

    public ExecutionEnvironment(String environmentId, ExecutionEnvironmentKind kind, String version,
                                 ExecutionEnvironmentStatus status, List<String> capabilities,
                                 String adapterProviderId, String configReference, String createdBy,
                                 Instant createdAt, String updatedBy, Instant updatedAt) {
        this(environmentId, kind, version, status, capabilities, adapterProviderId, configReference,
                createdBy, createdAt, updatedBy, updatedAt, 1);
    }

    public ExecutionEnvironment withStatus(ExecutionEnvironmentStatus nextStatus, String actor, Instant updatedAt) {
        final int nextRevision;
        try {
            nextRevision = Math.addExact(revision, 1);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("execution environment revision overflow", exception);
        }
        return new ExecutionEnvironment(environmentId, kind, version, nextStatus, capabilities, adapterProviderId,
                configReference, createdBy, createdAt, actor, updatedAt, nextRevision);
    }

    private static List<String> normalizeCapabilities(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new IllegalArgumentException("capabilities must contain at most 20 items");
        return values.stream().map(value -> normalizeText(value, "capability", 64))
                .peek(value -> {
                    if (!value.matches(IDENTIFIER)) {
                        throw new IllegalArgumentException("capability must be a bounded identifier");
                    }
                }).distinct().sorted().toList();
    }

    private static String normalizeConfigReference(String value) {
        String normalized = value == null ? "" : value.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (RAW_CREDENTIAL_PREFIXES.stream().anyMatch(lower::startsWith)
                || lower.contains("token=") || lower.contains("password=") || lower.contains("secret=")) {
            throw new IllegalArgumentException("configReference must reference a secret, not contain a raw credential");
        }
        if (!normalized.isBlank() && !normalized.startsWith("secret://")) {
            throw new IllegalArgumentException("configReference must be blank or start with secret://");
        }
        return normalized;
    }

    private static String normalizeOptionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !normalized.matches(IDENTIFIER)) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeIdentifier(String value, String field) {
        String normalized = normalizeText(value, field, 128);
        if (!normalized.matches(IDENTIFIER)) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String normalizeText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " exceeds maximum length");
        return normalized;
    }
}
