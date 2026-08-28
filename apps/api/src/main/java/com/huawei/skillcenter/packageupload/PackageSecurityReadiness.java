package com.huawei.skillcenter.packageupload;

import java.time.Instant;
import java.util.List;

/** Admin-safe readiness projection for the external package security gate. */
public record PackageSecurityReadiness(
        String mode,
        String status,
        String reasonCode,
        String scannerId,
        String scannerVersion,
        Instant checkedAt,
        List<String> capabilities,
        List<String> missingCapabilities) {
    public PackageSecurityReadiness(String mode,
                                    String status,
                                    String reasonCode,
                                    String scannerId,
                                    String scannerVersion,
                                    Instant checkedAt) {
        this(mode, status, reasonCode, scannerId, scannerVersion, checkedAt, List.of(), List.of());
    }

    public PackageSecurityReadiness {
        mode = mode == null || mode.isBlank() ? "DISABLED" : mode.trim();
        status = status == null || status.isBlank() ? "DEGRADED" : status.trim();
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "EXTERNAL_SECURITY_SCANNER_UNKNOWN" : reasonCode.trim();
        scannerId = scannerId == null || scannerId.isBlank() ? "external-package-security" : scannerId.trim();
        scannerVersion = scannerVersion == null ? "" : scannerVersion.trim();
        checkedAt = checkedAt == null ? Instant.EPOCH : checkedAt;
        capabilities = capabilities == null ? List.of() : capabilities.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().sorted().toList();
        missingCapabilities = missingCapabilities == null ? List.of() : missingCapabilities.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().sorted().toList();
    }
}
