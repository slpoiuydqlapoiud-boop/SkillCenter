package com.huawei.skillcenter.packageupload;

public class PackageValidationException extends RuntimeException {
    private final PackageValidationResult result;

    public PackageValidationException(PackageValidationResult result) {
        super("Skill package validation failed");
        this.result = result;
    }

    public PackageValidationResult result() {
        return result;
    }
}
