package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackageSecurityScannerContractTest {
    @Test
    void legacyLocalResultRetainsLocalScannerProvenance() {
        PackageSecurityScanResult result = new PackageSecurityScanResult("PASSED", List.of());

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.scannerId()).isEqualTo("local-package-security");
        assertThat(result.scannerVersion()).isEqualTo("1");
    }

    @Test
    void externalResultCarriesSafeScannerProvenanceAndFindings() {
        PackageSecurityScanResult result = new PackageSecurityScanResult(
                "BLOCKED", "approved-malware-engine", "2026.1",
                List.of(new PackageSecurityFinding("MALWARE", "skill/SKILL.md", "HIGH", "safe summary")));

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.scannerId()).isEqualTo("approved-malware-engine");
        assertThat(result.scannerVersion()).isEqualTo("2026.1");
        assertThat(result.findings()).hasSize(1);
    }

    @Test
    void scannerContractExposesHealthAndScanWithoutResponseBodyContract() {
        ExternalPackageSecurityScanner scanner = new ExternalPackageSecurityScanner() {
            @Override
            public String scannerId() {
                return "approved-malware-engine";
            }

            @Override
            public String scannerVersion() {
                return "2026.1";
            }

            @Override
            public ExternalPackageSecurityScannerHealth health() {
                return ExternalPackageSecurityScannerHealth.contractOnly();
            }

            @Override
            public PackageSecurityScanResult scan(Path zipPath) {
                return new PackageSecurityScanResult("NOT_SCANNED", scannerId(), scannerVersion(), List.of());
            }
        };

        assertThat(scanner.health().status()).isEqualTo("CONTRACT_ONLY");
        assertThat(scanner.health().reasonCode()).isEqualTo("EXTERNAL_SECURITY_SCANNER_CONTRACT_ONLY");
        assertThat(scanner.capabilities()).isEmpty();
        assertThat(scanner.scan(Path.of("package.zip")).scannerId()).isEqualTo("approved-malware-engine");
    }
}
