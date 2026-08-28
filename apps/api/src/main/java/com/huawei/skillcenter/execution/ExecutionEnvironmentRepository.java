package com.huawei.skillcenter.execution;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for the Agent Runtime/MCP/LLM execution-environment catalog. */
public interface ExecutionEnvironmentRepository {
    List<ExecutionEnvironment> findAll(ExecutionEnvironmentKind kind, ExecutionEnvironmentStatus status);

    Optional<ExecutionEnvironment> find(ExecutionEnvironmentKind kind, String environmentId);

    ExecutionEnvironment create(ExecutionEnvironment value);

    ExecutionEnvironment replace(ExecutionEnvironment value, int expectedRevision);

    default ExecutionEnvironment replace(ExecutionEnvironment value) {
        if (value == null) throw new IllegalArgumentException("execution environment must not be null");
        return replace(value, value.revision() - 1);
    }
}
