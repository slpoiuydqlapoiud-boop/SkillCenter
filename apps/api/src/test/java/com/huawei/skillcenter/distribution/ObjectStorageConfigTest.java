package com.huawei.skillcenter.distribution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStorageConfigTest {
    @Test
    void rejectsUnsafeBucketNamesThatCouldEscapeObjectPath() {
        assertThatThrownBy(() -> new ObjectStorageConfig(
                "https://objects.example.com", "bucket/../other", "us-east-1", "skills",
                "secret://env/ACCESS", "secret://env/SECRET"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
