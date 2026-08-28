package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.governance.SkillVersion;
import com.huawei.skillcenter.skill.SkillCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ArtifactPackageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void missingBoundArtifactFailsClosedInsteadOfGeneratingReplacement() {
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        ArtifactPackageService service = new ArtifactPackageService(catalog);
        SkillVersion version = new SkillVersion(
                "package-1", "skill-1", "1.0.0", "published", "a".repeat(64), 10,
                tempDir.resolve("missing.zip").toString(), "alice", Instant.now(),
                "reviewer", Instant.now(), "review-1");

        assertThrows(ArtifactNotFoundException.class,
                () -> service.metadata("skill-1", "1.0.0", version));
        verifyNoInteractions(catalog);
    }

    @Test
    void boundOpaqueReferenceUsesTheStoragePortForManifestMetadata() {
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        ArtifactStorage storage = mock(ArtifactStorage.class);
        ArtifactPackageService service = new ArtifactPackageService(catalog, storage);
        SkillVersion version = new SkillVersion(
                "package-2", "skill-2", "2.0.0", "published", "b".repeat(64), 20,
                "local://sha256/" + "b".repeat(64) + ".zip", "alice", Instant.now(),
                "reviewer", Instant.now(), "review-2");
        when(storage.inspect(version.artifactPath(), version.sha256()))
                .thenReturn(new ArtifactStorage.ArtifactMetadata(version.sha256(), version.sizeBytes()));

        ArtifactPackageService.ArtifactMetadata metadata = service.metadata("skill-2", "2.0.0", version);

        assertThat(metadata.sha256()).isEqualTo(version.sha256());
        assertThat(metadata.sizeBytes()).isEqualTo(version.sizeBytes());
    }
}
