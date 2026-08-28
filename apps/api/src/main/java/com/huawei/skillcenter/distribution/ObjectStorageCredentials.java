package com.huawei.skillcenter.distribution;

/** Resolved credentials held only in memory for one outbound request. */
public record ObjectStorageCredentials(String accessKeyId, String secretAccessKey) {
    public ObjectStorageCredentials {
        if (accessKeyId == null || accessKeyId.isBlank() || secretAccessKey == null || secretAccessKey.isBlank()) {
            throw new ArtifactStorageUnavailableException("object-storage",
                    "ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED");
        }
    }
}
