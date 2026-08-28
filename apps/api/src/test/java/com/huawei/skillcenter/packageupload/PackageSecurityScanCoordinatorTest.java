package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackageSecurityScanCoordinatorTest {
    private static final Path PACKAGE = Path.of("skill.zip");

    @Test
    void disabledModeKeepsLocalPassAndDoesNotCallExternalScanner() {
        CountingExternalScanner external = new CountingExternalScanner("READY", new PackageSecurityScanResult("PASSED", "vendor", "2", List.of()));
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                local("PASSED"), external, PackageSecurityExternalMode.DISABLED);

        PackageSecurityScanResult result = coordinator.scan(PACKAGE);

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.scannerId()).isEqualTo("local-package-security");
        assertThat(external.calls).isZero();
    }

    @Test
    void requiredModeFailsClosedWhenExternalScannerIsContractOnly() {
        CountingExternalScanner external = new CountingExternalScanner("CONTRACT_ONLY", new PackageSecurityScanResult("PASSED", "vendor", "2", List.of()));
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                local("PASSED"), external, PackageSecurityExternalMode.REQUIRED);

        PackageSecurityScanResult result = coordinator.scan(PACKAGE);

        assertThat(result.status()).isEqualTo("NOT_SCANNED");
        assertThat(result.scannerId()).isEqualTo("vendor");
        assertThat(result.findings()).anyMatch(finding -> finding.code().equals("EXTERNAL_SECURITY_SCAN_UNAVAILABLE"));
        assertThat(external.calls).isZero();
    }

    @Test
    void requiredModeUsesExternalPassAndPersistsItsProvenance() {
        CountingExternalScanner external = new CountingExternalScanner("READY", new PackageSecurityScanResult("PASSED", "vendor", "2", List.of()));
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                local("PASSED"), external, PackageSecurityExternalMode.REQUIRED);

        PackageSecurityScanResult result = coordinator.scan(PACKAGE);

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.scannerId()).isEqualTo("vendor");
        assertThat(result.scannerVersion()).isEqualTo("2");
        assertThat(external.calls).isEqualTo(1);
    }

    @Test
    void localBlockShortCircuitsExternalScanner() {
        CountingExternalScanner external = new CountingExternalScanner("READY", new PackageSecurityScanResult("PASSED", "vendor", "2", List.of()));
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                local("BLOCKED"), external, PackageSecurityExternalMode.REQUIRED);

        PackageSecurityScanResult result = coordinator.scan(PACKAGE);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.scannerId()).isEqualTo("local-package-security");
        assertThat(external.calls).isZero();
    }

    @Test
    void externalBlockWithoutDetailsStillProducesStableFinding() {
        CountingExternalScanner external = new CountingExternalScanner("READY", new PackageSecurityScanResult("BLOCKED", "vendor", "2", List.of()));
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                local("PASSED"), external, PackageSecurityExternalMode.REQUIRED);

        PackageSecurityScanResult result = coordinator.scan(PACKAGE);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.findings()).singleElement().extracting(PackageSecurityFinding::code)
                .isEqualTo("EXTERNAL_SECURITY_SCAN_BLOCKED");
    }

    @Test
    void externalErrorProducesSafeUnavailableFinding() {
        ExternalPackageSecurityScanner external = new CountingExternalScanner("READY", null) {
            @Override
            public PackageSecurityScanResult scan(Path zipPath) {
                throw new IllegalStateException("raw vendor response");
            }
        };
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                local("PASSED"), external, PackageSecurityExternalMode.REQUIRED);

        PackageSecurityScanResult result = coordinator.scan(PACKAGE);

        assertThat(result.status()).isEqualTo("NOT_SCANNED");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("EXTERNAL_SECURITY_SCAN_UNAVAILABLE");
            assertThat(finding.reason()).doesNotContain("raw vendor response");
        });
    }

    @Test
    void requiredModeFailsClosedWhenExternalScannerCoverageIsIncomplete() {
        CountingExternalScanner external = new CountingExternalScanner("READY", new PackageSecurityScanResult("PASSED", "vendor", "2", List.of())) {
            @Override
            public Set<PackageSecurityScanCapability> capabilities() {
                return Set.of(PackageSecurityScanCapability.MALWARE);
            }
        };
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                local("PASSED"), external, PackageSecurityExternalMode.REQUIRED);

        PackageSecurityScanResult result = coordinator.scan(PACKAGE);

        assertThat(result.status()).isEqualTo("NOT_SCANNED");
        assertThat(result.findings()).singleElement().extracting(PackageSecurityFinding::code)
                .isEqualTo("EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE");
        assertThat(external.calls).isZero();
    }

    private PackageSecurityScanService local(String status) {
        return new PackageSecurityScanService() {
            @Override
            public PackageSecurityScanResult scan(Path zipPath) {
                return new PackageSecurityScanResult(status, List.of());
            }
        };
    }

    private static class CountingExternalScanner implements ExternalPackageSecurityScanner {
        private final String healthStatus;
        private final PackageSecurityScanResult result;
        private int calls;

        private CountingExternalScanner(String healthStatus, PackageSecurityScanResult result) {
            this.healthStatus = healthStatus;
            this.result = result;
        }

        @Override
        public String scannerId() {
            return "vendor";
        }

        @Override
        public String scannerVersion() {
            return "2";
        }

        @Override
        public ExternalPackageSecurityScannerHealth health() {
            return new ExternalPackageSecurityScannerHealth(healthStatus, "TEST");
        }

        @Override
        public Set<PackageSecurityScanCapability> capabilities() {
            return Set.of(PackageSecurityScanCapability.values());
        }

        @Override
        public PackageSecurityScanResult scan(Path zipPath) {
            calls++;
            return result;
        }
    }
}
