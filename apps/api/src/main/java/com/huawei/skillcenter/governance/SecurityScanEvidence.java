package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.packageupload.PackageValidationResult;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable, metadata-only security scan provenance carried with a Skill version. */
public record SecurityScanEvidence(
        String status,
        String scannerId,
        String scannerVersion,
        List<Finding> findings) {
    private static final Set<String> STATUSES = Set.of("PASSED", "BLOCKED", "NOT_SCANNED");
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH");

    public SecurityScanEvidence {
        status = STATUSES.contains(status) ? status : "NOT_SCANNED";
        scannerId = normalize(scannerId, "legacy-compatible");
        scannerVersion = normalize(scannerVersion, "");
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static SecurityScanEvidence legacy() {
        return new SecurityScanEvidence("NOT_SCANNED", "legacy-compatible", "", List.of());
    }

    public static SecurityScanEvidence from(PackageValidationResult result) {
        if (result == null) return legacy();
        String scannerId = result.securityScannerId();
        String scannerVersion = result.securityScannerVersion();
        List<Finding> findings = result.securityFindings() == null ? List.of() : result.securityFindings().stream()
                .map(finding -> new Finding(finding.code(), finding.path(), finding.severity()))
                .toList();
        return new SecurityScanEvidence(result.securityStatus(), scannerId, scannerVersion, findings);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record Finding(String code, String path, String severity) {
        public Finding {
            code = normalize(code, "UNKNOWN");
            path = normalize(path, "unknown");
            String normalizedSeverity = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
            severity = SEVERITIES.contains(normalizedSeverity) ? normalizedSeverity : "INFO";
        }
    }
}
