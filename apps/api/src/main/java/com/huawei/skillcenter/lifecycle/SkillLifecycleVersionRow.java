package com.huawei.skillcenter.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SkillLifecycleVersionRow(
        String skillId, String version, String packageId, String status, String sha256, long sizeBytes,
        String uploadedBy, Instant uploadedAt, Instant publishedAt, String riskLevel,
        String securityStatus, String securityScannerId, String securityScannerVersion,
        List<SkillLifecycleSecurityFindingRow> securityFindings
) {
    private static final Set<String> SECURITY_STATUSES = Set.of("PASSED", "BLOCKED", "NOT_SCANNED");

    public SkillLifecycleVersionRow(String skillId, String version, String packageId, String status, String sha256,
                                    long sizeBytes, String uploadedBy, Instant uploadedAt, Instant publishedAt,
                                    String riskLevel) {
        this(skillId, version, packageId, status, sha256, sizeBytes, uploadedBy, uploadedAt, publishedAt, riskLevel,
                "NOT_SCANNED", "legacy-compatible", "", List.of());
    }

    public SkillLifecycleVersionRow {
        skillId = SkillLifecycleProjectionInput.requireIdentifier(skillId, "skillId");
        version = SkillLifecycleProjectionInput.requireVersion(version, "version");
        packageId = SkillLifecycleProjectionInput.requireIdentifier(packageId, "packageId");
        status = SkillLifecycleProjectionInput.requireCode(status, "status");
        sha256 = SkillLifecycleProjectionInput.requireSha256(sha256, "sha256");
        sizeBytes = SkillLifecycleProjectionInput.requireNonNegativeLong(sizeBytes, "sizeBytes");
        uploadedBy = SkillLifecycleProjectionInput.requireIdentifier(uploadedBy, "uploadedBy");
        uploadedAt = SkillLifecycleProjectionInput.requireInstant(uploadedAt, "uploadedAt");
        riskLevel = SkillLifecycleProjectionInput.requireCode(riskLevel, "riskLevel");
        securityStatus = normalizeSecurityStatus(securityStatus);
        securityScannerId = SkillLifecycleProjectionInput.requireCode(securityScannerId, "securityScannerId");
        securityScannerVersion = securityScannerVersion == null || securityScannerVersion.isBlank()
                ? "" : SkillLifecycleProjectionInput.requireCode(securityScannerVersion, "securityScannerVersion");
        securityFindings = securityFindings == null ? List.of() : List.copyOf(securityFindings);
        if (securityFindings.stream().anyMatch(finding -> finding == null)) {
            throw new IllegalArgumentException("securityFindings must not contain null rows");
        }
    }

    private static String normalizeSecurityStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SECURITY_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("securityStatus must be a known status");
        }
        return normalized;
    }

    public SkillLifecycleVersionRow withSecurityFindings(List<SkillLifecycleSecurityFindingRow> findings) {
        return new SkillLifecycleVersionRow(skillId, version, packageId, status, sha256, sizeBytes, uploadedBy,
                uploadedAt, publishedAt, riskLevel, securityStatus, securityScannerId, securityScannerVersion,
                findings);
    }
}
