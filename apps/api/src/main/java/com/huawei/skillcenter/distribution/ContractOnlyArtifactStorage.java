package com.huawei.skillcenter.distribution;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Explicit object-storage placeholder. It prevents an object backend selection
 * from silently falling back to the local filesystem before a real adapter is installed.
 */
public class ContractOnlyArtifactStorage implements ArtifactStorage, ArtifactStorageHealth,
        ArtifactStorageConnectivityProbe, ArtifactStorageIdentity {
    public static final String BACKEND = "object-storage";
    public static final String IDENTITY = "object-storage-contract";
    public static final String UNAVAILABLE_CODE = "ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED";

    @Override
    public StoredArtifact store(Path source, String packageId) throws IOException {
        throw unavailable();
    }

    @Override
    public ArtifactMetadata inspect(String reference, String expectedSha256) {
        throw unavailable();
    }

    @Override
    public ArtifactResource open(String reference, String expectedSha256) {
        throw unavailable();
    }

    @Override
    public ArtifactStorageReadiness readiness() {
        return new ArtifactStorageReadiness(BACKEND, "NOT_READY", UNAVAILABLE_CODE,
                "对象存储适配器尚未配置");
    }

    @Override
    public ArtifactStorageProbeResult probe() {
        return new ArtifactStorageProbeResult(BACKEND, "NOT_CONFIGURED", UNAVAILABLE_CODE,
                null, 0, java.time.Instant.now());
    }

    @Override
    public String identity() {
        return IDENTITY;
    }

    private ArtifactStorageUnavailableException unavailable() {
        return new ArtifactStorageUnavailableException(BACKEND, UNAVAILABLE_CODE);
    }
}
