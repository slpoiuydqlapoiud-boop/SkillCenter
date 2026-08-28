package com.huawei.skillcenter.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.access.SkillScopeStore;
import com.huawei.skillcenter.governance.GovernanceSnapshot;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SecurityScanEvidence;
import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.relationship.SkillRelationStore;
import com.huawei.skillcenter.release.ReleaseRecordStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLifecycleProjectionSourceTest {
    private static final Instant AT = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void projectsSafeSecurityEvidenceFromGovernanceVersion() throws Exception {
        SecurityScanEvidence evidence = new SecurityScanEvidence(
                "BLOCKED", "local-package-security", "1",
                List.of(new SecurityScanEvidence.Finding("SECRET_PATTERN", "skill/SKILL.md", "HIGH")));

        SkillLifecycleVersionRow row = source(version(evidence)).read().versions().getFirst();

        assertThat(row.securityStatus()).isEqualTo("BLOCKED");
        assertThat(row.securityScannerId()).isEqualTo("local-package-security");
        assertThat(row.securityScannerVersion()).isEqualTo("1");
        assertThat(row.securityFindings()).containsExactly(
                new SkillLifecycleSecurityFindingRow("SECRET_PATTERN", "skill/SKILL.md", "HIGH"));
    }

    @Test
    void projectsLegacyVersionAsNotScannedWithoutFindings() throws Exception {
        SkillLifecycleVersionRow row = source(new SkillVersion(
                "pkg-legacy", "legacy-skill", "1.0.0", "pending_review", "a".repeat(64),
                12L, "artifact.zip", "uploader", AT, null, null, "review-legacy")).read().versions().getFirst();

        assertThat(row.securityStatus()).isEqualTo("NOT_SCANNED");
        assertThat(row.securityScannerId()).isEqualTo("legacy-compatible");
        assertThat(row.securityScannerVersion()).isEmpty();
        assertThat(row.securityFindings()).isEmpty();
    }

    private SkillLifecycleProjectionSource source(SkillVersion version) throws Exception {
        Path directory = Files.createTempDirectory("lifecycle-security-source-");
        GovernanceStore governance = new GovernanceStore(directory.resolve("governance.json"), List.of()) {
            @Override
            public GovernanceSnapshot snapshot() {
                return new GovernanceSnapshot(List.of(version), List.of(), List.of(), List.of());
            }
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new SkillLifecycleProjectionSource(
                governance,
                new ReleaseRecordStore(mapper, directory.resolve("releases.json").toString()),
                new SkillScopeStore(mapper, directory.resolve("scopes.json").toString()),
                new SkillRelationStore(mapper, directory.resolve("relations.json").toString()),
                Clock.fixed(AT, ZoneOffset.UTC));
    }

    private SkillVersion version(SecurityScanEvidence evidence) {
        return new SkillVersion(
                "pkg-evidence", "evidence-skill", "1.0.0", "published", "b".repeat(64),
                12L, "artifact.zip", "uploader", AT, "reviewer", AT, "review-evidence",
                null, null, null, null, "low", evidence);
    }
}
