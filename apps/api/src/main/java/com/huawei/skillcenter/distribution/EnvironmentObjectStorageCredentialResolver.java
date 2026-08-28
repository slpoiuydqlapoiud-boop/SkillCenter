package com.huawei.skillcenter.distribution;

import java.util.Map;

/** Resolves only secret://env/NAME references; deployments may replace this bean with a secret manager. */
public final class EnvironmentObjectStorageCredentialResolver implements ObjectStorageCredentialResolver {
    private static final String PREFIX = "secret://env/";
    private final Map<String, String> variables;

    public EnvironmentObjectStorageCredentialResolver() {
        this(System.getenv());
    }

    public EnvironmentObjectStorageCredentialResolver(Map<String, String> variables) {
        this.variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    @Override
    public ObjectStorageCredentials resolve(String accessKeyIdRef, String secretAccessKeyRef) {
        return new ObjectStorageCredentials(resolveReference(accessKeyIdRef), resolveReference(secretAccessKeyRef));
    }

    private String resolveReference(String reference) {
        String normalized = reference == null ? "" : reference.trim();
        if (!normalized.startsWith(PREFIX)) throw unavailable();
        String name = normalized.substring(PREFIX.length());
        if (!name.matches("[A-Z_][A-Z0-9_]{0,127}")) throw unavailable();
        String value = variables.get(name);
        if (value == null || value.isBlank()) throw unavailable();
        return value;
    }

    private ArtifactStorageUnavailableException unavailable() {
        return new ArtifactStorageUnavailableException("object-storage",
                "ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED");
    }
}
