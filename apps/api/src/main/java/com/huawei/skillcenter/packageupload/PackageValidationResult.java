package com.huawei.skillcenter.packageupload;

import java.util.List;
import java.util.Set;

public record PackageValidationResult(
        boolean valid,
        String skillId,
        String version,
        String sha256,
        long sizeBytes,
        List<ValidationError> errors,
        String riskLevel,
        String securityStatus,
        List<PackageSecurityFinding> securityFindings,
        String securityScannerId,
        String securityScannerVersion
) {
    public PackageValidationResult(boolean valid,
                                   String skillId,
                                   String version,
                                   String sha256,
                                   long sizeBytes,
                                   List<ValidationError> errors) {
        this(valid, skillId, version, sha256, sizeBytes, errors, "low", "NOT_SCANNED", List.of(),
                "legacy-compatible", "");
    }

    public PackageValidationResult(boolean valid,
                                   String skillId,
                                   String version,
                                   String sha256,
                                   long sizeBytes,
                                   List<ValidationError> errors,
                                   String riskLevel) {
        this(valid, skillId, version, sha256, sizeBytes, errors, riskLevel, "NOT_SCANNED", List.of(),
                "legacy-compatible", "");
    }

    public PackageValidationResult(boolean valid,
                                   String skillId,
                                   String version,
                                   String sha256,
                                   long sizeBytes,
                                   List<ValidationError> errors,
                                   String riskLevel,
                                   String securityStatus,
                                   List<PackageSecurityFinding> securityFindings) {
        this(valid, skillId, version, sha256, sizeBytes, errors, riskLevel, securityStatus, securityFindings,
                "PASSED".equals(securityStatus) || "BLOCKED".equals(securityStatus)
                        ? "local-package-security" : "legacy-compatible",
                "PASSED".equals(securityStatus) || "BLOCKED".equals(securityStatus) ? "1" : "");
    }

    public PackageValidationResult {
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel.trim().toLowerCase(java.util.Locale.ROOT);
        securityStatus = Set.of("PASSED", "BLOCKED", "NOT_SCANNED").contains(securityStatus)
                ? securityStatus : "NOT_SCANNED";
        securityFindings = securityFindings == null ? List.of() : List.copyOf(securityFindings);
        securityScannerId = securityScannerId == null || securityScannerId.isBlank()
                ? "legacy-compatible" : securityScannerId.trim();
        securityScannerVersion = securityScannerVersion == null ? "" : securityScannerVersion.trim();
    }

    public record ValidationError(String code, String path, String reason) {
    }
}
