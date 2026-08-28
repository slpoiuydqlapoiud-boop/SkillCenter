package com.huawei.skillcenter.packageupload;

public record StoredPackage(String packageId, String reference) {
    /**
     * Compatibility accessor for existing governance callers and historical
     * test fixtures. New values are opaque storage references, not paths.
     */
    public String path() {
        return reference;
    }
}
