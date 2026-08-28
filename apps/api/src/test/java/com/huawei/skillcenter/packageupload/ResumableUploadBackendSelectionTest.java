package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "skill-center.package-upload-backend=distributed")
class ResumableUploadBackendSelectionTest {
    @Autowired
    private ResumableUploadStore store;

    @Test
    void selectsDistributedStoreOnlyWhenExplicitlyConfigured() {
        assertThat(store).isInstanceOf(DistributedResumableUploadStore.class);
    }
}
