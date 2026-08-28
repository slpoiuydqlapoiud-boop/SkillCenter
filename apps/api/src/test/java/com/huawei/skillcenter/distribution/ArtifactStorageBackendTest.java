package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.packageupload.LocalPackageStorage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStorageBackendTest {
    @Test
    void contractOnlyObjectStorageNeverFallsBackToLocalFiles() {
        ArtifactStorage storage = new ContractOnlyArtifactStorage();

        assertThat(storage).isInstanceOf(ArtifactStorageHealth.class);
        ArtifactStorageReadiness readiness = ((ArtifactStorageHealth) storage).readiness();
        assertThat(readiness.backend()).isEqualTo("object-storage");
        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED");

        assertThatThrownBy(() -> storage.store(Path.of("missing.zip"), "pkg-1"))
                .isInstanceOf(ArtifactStorageUnavailableException.class)
                .hasMessageContaining("ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED");
    }

    @Test
    void localStorageReportsDevelopmentOnlyReadiness() {
        LocalPackageStorage storage = new LocalPackageStorage("./target/test-artifacts");

        ArtifactStorageReadiness readiness = storage.readiness();

        assertThat(readiness.backend()).isEqualTo("local");
        assertThat(readiness.status()).isEqualTo("DEGRADED");
        assertThat(readiness.reasonCode()).isEqualTo("ARTIFACT_STORAGE_LOCAL_ONLY");
        assertThat(readiness.summary()).doesNotContain("target");
    }
}
