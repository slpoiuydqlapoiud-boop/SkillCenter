package com.huawei.skillcenter.persistence;

public enum PersistenceArtifactState {
    READY,
    MISSING,
    CORRUPTED,
    MIGRATION_REQUIRED,
    OPTIONAL_MISSING
}
