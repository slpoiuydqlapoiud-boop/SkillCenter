package com.huawei.skillcenter.operations;

/** Read-only health projection for the configured Skill search index backend. */
public interface SkillSearchBackendHealth {
    SkillSearchBackendReadiness readiness();
}
