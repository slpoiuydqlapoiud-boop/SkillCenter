package com.huawei.skillcenter.release;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "skill-center.release-target.mode", havingValue = "mock", matchIfMissing = true)
public class MockReleaseTarget implements ReleaseTarget {
    @Override
    public ReleaseTargetResult promote(ReleaseRecord release) {
        return execute(release);
    }

    @Override
    public ReleaseTargetResult rollback(ReleaseRecord release) {
        return execute(release);
    }

    private ReleaseTargetResult execute(ReleaseRecord release) {
        if (release == null) return ReleaseTargetResult.failure("RELEASE_REQUIRED");
        if (release.releaseId().toLowerCase().contains("fail")) {
            return ReleaseTargetResult.failure("MOCK_TARGET_FAILED");
        }
        return ReleaseTargetResult.success("mock/" + release.releaseId());
    }
}
