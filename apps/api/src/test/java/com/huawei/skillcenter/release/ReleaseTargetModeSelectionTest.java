package com.huawei.skillcenter.release;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "skill-center.release-target.mode=http",
        "skill-center.release-target.endpoint=",
        "skill-center.release-target.credential-ref="
})
class ReleaseTargetModeSelectionTest {
    @Autowired
    private ReleaseTarget target;

    @Test
    void explicitHttpModeDoesNotSilentlyUseMock() {
        assertThat(target).isInstanceOf(HttpReleaseTarget.class);
    }
}
