package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.packageupload.PackageSecurityFinding;
import com.huawei.skillcenter.packageupload.PackageValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityScanEvidenceTest {
    @Test
    void preservesExternalScannerProvenanceWithoutPersistingFindingReason() {
        PackageValidationResult result = new PackageValidationResult(
                false, "demo-skill", "1.0.0", "a".repeat(64), 42, List.of(), "low", "BLOCKED",
                List.of(new PackageSecurityFinding("MALWARE", "skill/SKILL.md", "HIGH", "raw vendor response")),
                "approved-engine", "2026.1");

        SecurityScanEvidence evidence = SecurityScanEvidence.from(result);

        assertThat(evidence.scannerId()).isEqualTo("approved-engine");
        assertThat(evidence.scannerVersion()).isEqualTo("2026.1");
        assertThat(evidence.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("MALWARE");
            assertThat(finding.path()).isEqualTo("skill/SKILL.md");
            assertThat(finding.severity()).isEqualTo("HIGH");
            assertThat(finding.toString()).doesNotContain("raw vendor response");
        });
    }
}
