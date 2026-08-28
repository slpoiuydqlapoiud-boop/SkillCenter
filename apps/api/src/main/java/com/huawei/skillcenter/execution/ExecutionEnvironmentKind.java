package com.huawei.skillcenter.execution;

import java.util.Locale;

public enum ExecutionEnvironmentKind {
    AGENT_RUNTIME,
    MCP_SERVER,
    LLM_PROVIDER;

    public static ExecutionEnvironmentKind from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        try {
            return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').equals("AGENT_RUNTIME")
                    ? AGENT_RUNTIME : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').equals("MCP_SERVER")
                    ? MCP_SERVER : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').equals("LLM_PROVIDER")
                    ? LLM_PROVIDER : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("kind must be AGENT_RUNTIME, MCP_SERVER or LLM_PROVIDER");
        }
    }
}
