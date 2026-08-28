package com.huawei.skillcenter.quality;

import java.util.Map;

/**
 * Deployment baseline resolver. Only secret://env/NAME references are accepted;
 * production deployments may replace this bean with a Secret Manager resolver.
 */
public final class EnvironmentProviderCredentialResolver implements ProviderCredentialResolver {
    private static final String PREFIX = "secret://env/";
    private final Map<String, String> variables;

    public EnvironmentProviderCredentialResolver() {
        this(System.getenv());
    }

    public EnvironmentProviderCredentialResolver(Map<String, String> variables) {
        this.variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    @Override
    public String resolve(String credentialRef) {
        String reference = credentialRef == null ? "" : credentialRef.trim();
        if (!reference.startsWith(PREFIX)) {
            throw unavailable();
        }
        String name = reference.substring(PREFIX.length());
        if (!name.matches("[A-Z_][A-Z0-9_]{0,127}")) {
            throw unavailable();
        }
        String value = variables.get(name);
        if (value == null || value.isBlank()) {
            throw unavailable();
        }
        return value;
    }

    private static ProviderUnavailableException unavailable() {
        return new ProviderUnavailableException("provider-credential", "EXTERNAL_ADAPTER_NOT_CONFIGURED");
    }
}
