package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.governance.SecurityScanEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillLifecycleProjectionHasherTest {
    private static final Instant AT = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void sameFactsInDifferentCollectionOrderProduceTheSameHash() {
        SkillLifecycleProjectionInput first = input(version("skill-a", "1.0.0", "PUBLISHED", "pkg-a"),
                version("skill-b", "1.0.0", "PUBLISHED", "pkg-b"));
        SkillLifecycleProjectionInput second = input(version("skill-b", "1.0.0", "PUBLISHED", "pkg-b"),
                version("skill-a", "1.0.0", "PUBLISHED", "pkg-a"));

        assertThat(SkillLifecycleProjectionHasher.hash(first))
                .isEqualTo(SkillLifecycleProjectionHasher.hash(second));
    }

    @Test
    void lifecycleContentChangeProducesDifferentHash() {
        SkillLifecycleProjectionInput original = input(version("skill-a", "1.0.0", "PUBLISHED", "pkg-a"));
        SkillLifecycleProjectionInput changed = input(version("skill-a", "1.0.0", "RETIRED", "pkg-a"));

        assertThat(SkillLifecycleProjectionHasher.hash(original))
                .isNotEqualTo(SkillLifecycleProjectionHasher.hash(changed));
    }

    @Test
    void securityEvidenceChangeProducesDifferentHash() {
        SkillLifecycleProjectionInput passed = input(versionWithEvidence(
                new SecurityScanEvidence("PASSED", "local-package-security", "1", List.of())));
        SkillLifecycleProjectionInput blocked = input(versionWithEvidence(
                new SecurityScanEvidence("BLOCKED", "local-package-security", "1",
                        List.of(new SecurityScanEvidence.Finding("SECRET_PATTERN", "SKILL.md", "HIGH")))));

        assertThat(SkillLifecycleProjectionHasher.hash(passed))
                .isNotEqualTo(SkillLifecycleProjectionHasher.hash(blocked));
    }

    @Test
    void securityFindingOrderDoesNotChangeHash() {
        SecurityScanEvidence.Finding first = new SecurityScanEvidence.Finding("BINARY_PAYLOAD", "bin/tool", "HIGH");
        SecurityScanEvidence.Finding second = new SecurityScanEvidence.Finding("SECRET_PATTERN", "SKILL.md", "HIGH");
        SkillLifecycleProjectionInput one = input(versionWithEvidence(
                new SecurityScanEvidence("BLOCKED", "local-package-security", "1", List.of(first, second))));
        SkillLifecycleProjectionInput two = input(versionWithEvidence(
                new SecurityScanEvidence("BLOCKED", "local-package-security", "1", List.of(second, first))));

        assertThat(SkillLifecycleProjectionHasher.hash(one))
                .isEqualTo(SkillLifecycleProjectionHasher.hash(two));
    }

    @Test
    void hashEscapesDelimitersAndReturnsLowercase64CharSha256() {
        SkillLifecycleProjectionInput escaped = input(
                version("skill-a", "1.0|beta\\build\nline\rreturn", "PUBLISHED", "pkg-a"));

        assertThat(SkillLifecycleProjectionHasher.hash(escaped))
                .matches("[a-f0-9]{64}")
                .isEqualTo("89b3cef2165212b2d920ef0b939d4e792d9ff90251b58505dd2c538f77302fe6");
    }

    @Test
    void inputMakesDefensiveCopiesOfRowLists() {
        SkillLifecycleVersionRow row = version("skill-a", "1.0.0", "PUBLISHED", "pkg-a");
        ArrayList<SkillLifecycleVersionRow> versions = mutableVersions(row);

        SkillLifecycleProjectionInput input = new SkillLifecycleProjectionInput(
                List.of(), versions, List.of(), List.of(), List.of());

        versions.clear();

        assertThat(input.versions()).containsExactly(row);
        assertThatThrownBy(() -> input.versions().add(row))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void inputRejectsNullRows() {
        ArrayList<SkillLifecycleVersionRow> versions = mutableVersions((SkillLifecycleVersionRow) null);

        assertThatThrownBy(() -> new SkillLifecycleProjectionInput(
                List.of(), versions, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("versions must not contain null rows");
    }

    @Test
    void snapshotMakesDefensiveCopiesOfRowLists() {
        SkillLifecycleVersionRow row = version("skill-a", "1.0.0", "PUBLISHED", "pkg-a");
        ArrayList<SkillLifecycleVersionRow> versions = mutableVersions(row);

        SkillLifecycleProjectionSnapshot snapshot = new SkillLifecycleProjectionSnapshot(
                "b".repeat(64), AT, List.of(), versions, List.of(), List.of(), List.of());

        versions.clear();

        assertThat(snapshot.versions()).containsExactly(row);
        assertThatThrownBy(() -> snapshot.versions().add(row))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotRejectsNullRows() {
        ArrayList<SkillLifecycleVersionRow> versions = mutableVersions((SkillLifecycleVersionRow) null);

        assertThatThrownBy(() -> new SkillLifecycleProjectionSnapshot(
                "b".repeat(64), AT, List.of(), versions, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("versions must not contain null rows");
    }

    @Test
    void snapshotRejectsBlankSourceMetadata() {
        assertThatThrownBy(() -> new SkillLifecycleProjectionSnapshot(
                "", AT, List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SkillLifecycleProjectionInput input(SkillLifecycleVersionRow... versions) {
        return new SkillLifecycleProjectionInput(List.of(), List.of(versions), List.of(), List.of(), List.of());
    }

    private SkillLifecycleVersionRow version(String skillId, String version, String status, String packageId) {
        return new SkillLifecycleVersionRow(skillId, version, packageId, status, "a".repeat(64),
                12L, "uploader", AT, AT, "low");
    }

    private SkillLifecycleVersionRow versionWithEvidence(SecurityScanEvidence evidence) {
        return new SkillLifecycleVersionRow("skill-a", "1.0.0", "pkg-a", "PUBLISHED", "a".repeat(64),
                12L, "uploader", AT, AT, "low", evidence.status(), evidence.scannerId(),
                evidence.scannerVersion(), evidence.findings().stream()
                        .map(finding -> new SkillLifecycleSecurityFindingRow(
                                finding.code(), finding.path(), finding.severity()))
                        .toList());
    }

    private ArrayList<SkillLifecycleVersionRow> mutableVersions(SkillLifecycleVersionRow... rows) {
        ArrayList<SkillLifecycleVersionRow> versions = new ArrayList<>();
        for (SkillLifecycleVersionRow row : rows) {
            versions.add(row);
        }
        return versions;
    }
}
