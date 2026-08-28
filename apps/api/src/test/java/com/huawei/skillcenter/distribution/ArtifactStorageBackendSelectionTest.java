package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.operations.ArtifactStorageReadinessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "skill-center.artifact-storage-backend=object-storage")
class ArtifactStorageBackendSelectionTest {
    @Autowired
    private ArtifactStorage storage;

    @Autowired
    private ArtifactStorageReadinessService readinessService;

    @Test
    void explicitObjectStorageSelectionDoesNotConstructLocalStorage() {
        assertThat(storage).isInstanceOf(ContractOnlyArtifactStorage.class);
        assertThat(storage).isNotInstanceOf(com.huawei.skillcenter.packageupload.LocalPackageStorage.class);
        assertThat(readinessService.readiness().reasonCode())
                .isEqualTo(ContractOnlyArtifactStorage.UNAVAILABLE_CODE);
    }
}
