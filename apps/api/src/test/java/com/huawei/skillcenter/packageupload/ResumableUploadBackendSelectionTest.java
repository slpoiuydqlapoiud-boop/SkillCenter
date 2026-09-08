package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "skill-center.package-upload-backend=distributed",
        "skill-center.artifact-storage-backend=object-storage",
        "skill-center.artifact-storage.mode=http",
        "skill-center.artifact-storage.endpoint=http://127.0.0.1:9000",
        "skill-center.artifact-storage.bucket=skill-packages",
        "skill-center.artifact-storage.access-key-id-ref=secret://env/SKILL_CENTER_MINIO_ROOT_USER",
        "skill-center.artifact-storage.secret-access-key-ref=secret://env/SKILL_CENTER_MINIO_ROOT_PASSWORD"
})
class ResumableUploadBackendSelectionTest {
    @Autowired
    private ResumableUploadStore store;

    @Test
    void selectsDistributedStoreOnlyWhenExplicitlyConfigured() {
        assertThat(store).isInstanceOf(DistributedResumableUploadStore.class);
    }
}
