package com.huawei.skillcenter.packageupload;

import java.nio.file.Path;
import java.util.Set;

/** Port for a production malware, sensitive-information, dependency, or license scanner. */
public interface ExternalPackageSecurityScanner {
    String scannerId();

    String scannerVersion();

    ExternalPackageSecurityScannerHealth health();

    /** Declares which independent security evidence domains this adapter covers. */
    default Set<PackageSecurityScanCapability> capabilities() {
        return Set.of();
    }

    PackageSecurityScanResult scan(Path zipPath);
}
