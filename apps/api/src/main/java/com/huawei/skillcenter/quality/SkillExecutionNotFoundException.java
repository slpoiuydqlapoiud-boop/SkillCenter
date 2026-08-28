package com.huawei.skillcenter.quality;

import java.util.UUID;

public class SkillExecutionNotFoundException extends RuntimeException {
    public SkillExecutionNotFoundException(UUID executionId) {
        super("Runner execution not found: " + executionId);
    }
}
