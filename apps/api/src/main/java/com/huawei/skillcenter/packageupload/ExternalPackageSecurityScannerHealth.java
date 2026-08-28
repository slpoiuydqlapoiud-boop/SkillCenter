package com.huawei.skillcenter.packageupload;

import java.util.Set;
import java.util.regex.Pattern;

/** Safe, metadata-only health state. Never carries endpoint, credential, or vendor response data. */
public record ExternalPackageSecurityScannerHealth(String status, String reasonCode) {
    private static final Set<String> STATUSES = Set.of("READY", "CONTRACT_ONLY", "NOT_CONFIGURED", "DEGRADED");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z0-9_]{1,96}");

    public ExternalPackageSecurityScannerHealth {
        status = STATUSES.contains(status) ? status : "DEGRADED";
        reasonCode = reasonCode == null || !REASON_CODE.matcher(reasonCode.trim()).matches()
                ? "EXTERNAL_SECURITY_SCANNER_UNKNOWN" : reasonCode.trim();
    }

    public static ExternalPackageSecurityScannerHealth contractOnly() {
        return new ExternalPackageSecurityScannerHealth("CONTRACT_ONLY", "EXTERNAL_SECURITY_SCANNER_CONTRACT_ONLY");
    }

    public static ExternalPackageSecurityScannerHealth notConfigured() {
        return new ExternalPackageSecurityScannerHealth("NOT_CONFIGURED", "EXTERNAL_SECURITY_SCANNER_NOT_CONFIGURED");
    }
}
