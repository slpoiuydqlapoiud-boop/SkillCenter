package com.huawei.skillcenter.packageupload;

import java.util.List;
import java.util.Set;

public record PackageSecurityScanResult(
        String status,
        String scannerId,
        String scannerVersion,
        List<PackageSecurityFinding> findings) {
    private static final Set<String> STATUSES = Set.of("PASSED", "BLOCKED", "NOT_SCANNED");

    public PackageSecurityScanResult(String status, List<PackageSecurityFinding> findings) {
        this(status,
                "PASSED".equals(status) || "BLOCKED".equals(status) ? "local-package-security" : "legacy-compatible",
                "PASSED".equals(status) || "BLOCKED".equals(status) ? "1" : "",
                findings);
    }

    public PackageSecurityScanResult {
        status = STATUSES.contains(status) ? status : "NOT_SCANNED";
        scannerId = scannerId == null || scannerId.isBlank() ? "legacy-compatible" : scannerId.trim();
        scannerVersion = scannerVersion == null ? "" : scannerVersion.trim();
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
