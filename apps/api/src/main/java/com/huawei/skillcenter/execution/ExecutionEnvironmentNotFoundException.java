package com.huawei.skillcenter.execution;

public class ExecutionEnvironmentNotFoundException extends RuntimeException {
    public ExecutionEnvironmentNotFoundException(ExecutionEnvironmentKind kind, String environmentId) {
        super("Execution environment not found: " + kind + "/" + environmentId);
    }
}
