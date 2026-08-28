package com.huawei.skillcenter.distribution;

/** Stable fail-closed error when an explicitly selected artifact backend is unavailable. */
public class ArtifactStorageUnavailableException extends RuntimeException {
    private final String backend;
    private final String code;

    public ArtifactStorageUnavailableException(String backend, String code) {
        super(code + ": " + backend);
        this.backend = backend == null ? "unknown" : backend;
        this.code = code == null ? "ARTIFACT_STORAGE_UNAVAILABLE" : code;
    }

    public String backend() {
        return backend;
    }

    public String code() {
        return code;
    }
}
