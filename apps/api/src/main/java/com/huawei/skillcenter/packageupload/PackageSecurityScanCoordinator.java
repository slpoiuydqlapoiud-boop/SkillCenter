package com.huawei.skillcenter.packageupload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Coordinates deterministic local checks with the optional fail-closed external scanner gate. */
@Service
public class PackageSecurityScanCoordinator {
    private static final String UNAVAILABLE_CODE = "EXTERNAL_SECURITY_SCAN_UNAVAILABLE";
    private static final String INCOMPLETE_CODE = "EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE";
    private static final Set<PackageSecurityScanCapability> REQUIRED_CAPABILITIES =
            Set.of(PackageSecurityScanCapability.values());
    private final PackageSecurityScanService localScanner;
    private final ExternalPackageSecurityScanner externalScanner;
    private final PackageSecurityExternalMode externalMode;

    public PackageSecurityScanCoordinator(PackageSecurityScanService localScanner,
                                          ExternalPackageSecurityScanner externalScanner,
                                          PackageSecurityExternalMode externalMode) {
        this.localScanner = localScanner == null ? new PackageSecurityScanService() : localScanner;
        this.externalScanner = externalScanner == null ? new ContractOnlyExternalPackageSecurityScanner() : externalScanner;
        this.externalMode = externalMode == null ? PackageSecurityExternalMode.DISABLED : externalMode;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PackageSecurityScanCoordinator(PackageSecurityScanService localScanner,
                                          ExternalPackageSecurityScanner externalScanner,
                                          @Value("${skill-center.package-security.external.mode:disabled}") String externalMode) {
        this(localScanner, externalScanner, PackageSecurityExternalMode.parse(externalMode));
    }

    public PackageSecurityScanResult scan(Path zipPath) {
        PackageSecurityScanResult local = localScanner.scan(zipPath);
        if (!"PASSED".equals(local.status()) || externalMode == PackageSecurityExternalMode.DISABLED) {
            return local;
        }
        ExternalPackageSecurityScannerHealth health = safeHealth();
        if (!"READY".equals(health.status())) {
            return unavailable();
        }
        if (!missingCapabilities().isEmpty()) {
            return unavailable(INCOMPLETE_CODE, "外部安全扫描覆盖能力不完整，上传已拒绝");
        }
        try {
            PackageSecurityScanResult external = externalScanner.scan(zipPath);
            if (external == null) return unavailable();
            List<PackageSecurityFinding> findings = safeFindings(external.findings());
            String status = "PASSED".equals(external.status()) ? "PASSED"
                    : "BLOCKED".equals(external.status()) ? "BLOCKED" : "NOT_SCANNED";
            if (findings.isEmpty() && "BLOCKED".equals(status)) {
                findings = List.of(new PackageSecurityFinding(
                        "EXTERNAL_SECURITY_SCAN_BLOCKED", "package", "HIGH", "外部安全扫描阻断上传"));
            } else if (findings.isEmpty() && "NOT_SCANNED".equals(status)) {
                findings = List.of(new PackageSecurityFinding(
                        UNAVAILABLE_CODE, "package", "HIGH", "外部安全扫描未完成，上传已拒绝"));
            }
            return new PackageSecurityScanResult(status, scannerId(), scannerVersion(), findings);
        } catch (RuntimeException exception) {
            return unavailable();
        }
    }

    public PackageSecurityExternalMode externalMode() {
        return externalMode;
    }

    public ExternalPackageSecurityScanner externalScanner() {
        return externalScanner;
    }

    public Set<PackageSecurityScanCapability> requiredCapabilities() {
        return REQUIRED_CAPABILITIES;
    }

    public PackageSecurityReadiness readiness(Instant checkedAt) {
        ExternalPackageSecurityScannerHealth health = safeHealth();
        List<String> capabilities = safeCapabilities().stream().map(Enum::name).sorted().toList();
        List<String> missing = missingCapabilities().stream().map(Enum::name).sorted().toList();
        if (externalMode == PackageSecurityExternalMode.DISABLED) {
            return new PackageSecurityReadiness("DISABLED", "DISABLED", "EXTERNAL_SECURITY_SCAN_DISABLED",
                    scannerId(), scannerVersion(), checkedAt, capabilities, missing);
        }
        if ("READY".equals(health.status()) && !missing.isEmpty()) {
            return new PackageSecurityReadiness("REQUIRED", "DEGRADED",
                    INCOMPLETE_CODE, scannerId(), scannerVersion(), checkedAt, capabilities, missing);
        }
        return new PackageSecurityReadiness("REQUIRED", health.status(), health.reasonCode(),
                scannerId(), scannerVersion(), checkedAt, capabilities, missing);
    }

    private ExternalPackageSecurityScannerHealth safeHealth() {
        try {
            ExternalPackageSecurityScannerHealth health = externalScanner.health();
            return health == null ? ExternalPackageSecurityScannerHealth.notConfigured() : health;
        } catch (RuntimeException exception) {
            return new ExternalPackageSecurityScannerHealth("DEGRADED", "EXTERNAL_SECURITY_SCANNER_HEALTH_FAILED");
        }
    }

    private PackageSecurityScanResult unavailable() {
        return unavailable(UNAVAILABLE_CODE, "外部安全扫描暂不可用，上传已拒绝");
    }

    private PackageSecurityScanResult unavailable(String code, String reason) {
        return new PackageSecurityScanResult("NOT_SCANNED", scannerId(), scannerVersion(), List.of(
                new PackageSecurityFinding(code, "package", "HIGH", reason)));
    }

    private List<PackageSecurityFinding> safeFindings(List<PackageSecurityFinding> findings) {
        if (findings == null) return List.of();
        List<PackageSecurityFinding> safe = new ArrayList<>();
        for (PackageSecurityFinding finding : findings) {
            if (finding == null) continue;
            safe.add(new PackageSecurityFinding(
                    bounded(finding.code(), "EXTERNAL_SECURITY_FINDING"),
                    bounded(finding.path(), "package"),
                    bounded(finding.severity(), "HIGH"),
                    "外部安全扫描发现风险"));
        }
        return List.copyOf(safe);
    }

    private String scannerId() {
        try {
            return bounded(externalScanner.scannerId(), "external-package-security");
        } catch (RuntimeException exception) {
            return "external-package-security";
        }
    }

    private String scannerVersion() {
        try {
            return bounded(externalScanner.scannerVersion(), "unknown");
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private Set<PackageSecurityScanCapability> safeCapabilities() {
        try {
            Set<PackageSecurityScanCapability> capabilities = externalScanner.capabilities();
            if (capabilities == null || capabilities.isEmpty()) return Set.of();
            return Set.copyOf(EnumSet.copyOf(capabilities));
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }

    private Set<PackageSecurityScanCapability> missingCapabilities() {
        EnumSet<PackageSecurityScanCapability> missing = EnumSet.copyOf(REQUIRED_CAPABILITIES);
        missing.removeAll(safeCapabilities());
        return Set.copyOf(missing);
    }

    private String bounded(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().replaceAll("[\\p{Cntrl}]", "");
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }
}
