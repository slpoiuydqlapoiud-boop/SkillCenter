package com.huawei.skillcenter.packageupload;

import java.util.List;

public record PackageValidationResult(
        boolean valid,
        String skillId,
        String version,
        String sha256,
        long sizeBytes,
        List<ValidationError> errors
) {
    public record ValidationError(String code, String path, String reason) {
    }
}
