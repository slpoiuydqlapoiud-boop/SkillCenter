package com.huawei.skillcenter.distribution;

/** Optional control-plane connectivity probe implemented by remote storage adapters. */
public interface ArtifactStorageConnectivityProbe {
    ArtifactStorageProbeResult probe();
}
