package com.huawei.skillcenter.packageupload;

import java.nio.file.Path;
import java.util.List;

/** Default integration seam. It deliberately performs no network or vendor call. */
public class ContractOnlyExternalPackageSecurityScanner implements ExternalPackageSecurityScanner {
    private static final String SCANNER_ID = "external-package-security";
    private static final String SCANNER_VERSION = "contract-v1";

    @Override
    public String scannerId() {
        return SCANNER_ID;
    }

    @Override
    public String scannerVersion() {
        return SCANNER_VERSION;
    }

    @Override
    public ExternalPackageSecurityScannerHealth health() {
        return ExternalPackageSecurityScannerHealth.contractOnly();
    }

    @Override
    public java.util.Set<PackageSecurityScanCapability> capabilities() {
        return java.util.Set.of();
    }

    @Override
    public PackageSecurityScanResult scan(Path zipPath) {
        return new PackageSecurityScanResult(
                "NOT_SCANNED", SCANNER_ID, SCANNER_VERSION,
                List.of(new PackageSecurityFinding(
                        "EXTERNAL_SECURITY_SCANNER_CONTRACT_ONLY", "package", "HIGH", "外部安全扫描引擎尚未接入")));
    }
}
