package com.huawei.skillcenter.release;

public interface ReleaseTarget {
    ReleaseTargetResult promote(ReleaseRecord release);

    ReleaseTargetResult rollback(ReleaseRecord release);
}
