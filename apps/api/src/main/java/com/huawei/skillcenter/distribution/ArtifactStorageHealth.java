package com.huawei.skillcenter.distribution;

/** Optional health projection implemented by concrete artifact storage adapters. */
public interface ArtifactStorageHealth {
    ArtifactStorageReadiness readiness();
}
