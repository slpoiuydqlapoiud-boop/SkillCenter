package com.huawei.skillcenter.distribution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "skill-center.artifact-storage-backend=object-storage",
        "skill-center.artifact-storage.mode=http",
        "skill-center.artifact-storage.endpoint=http://127.0.0.1:19090",
        "skill-center.artifact-storage.bucket=skill-center",
        "skill-center.artifact-storage.region=us-east-1",
        "skill-center.artifact-storage.prefix=artifacts",
        "skill-center.artifact-storage.access-key-id-ref=secret://env/SKILL_ACCESS_KEY",
        "skill-center.artifact-storage.secret-access-key-ref=secret://env/SKILL_SECRET_KEY"
})
class S3ArtifactStorageSelectionTest {
    @Autowired
    private ArtifactStorage storage;

    @Test
    void explicitHttpModeSelectsS3AdapterWithoutLocalFallback() {
        assertThat(storage).isInstanceOf(S3CompatibleArtifactStorage.class);
        assertThat(storage).isNotInstanceOf(com.huawei.skillcenter.packageupload.LocalPackageStorage.class);
    }
}
