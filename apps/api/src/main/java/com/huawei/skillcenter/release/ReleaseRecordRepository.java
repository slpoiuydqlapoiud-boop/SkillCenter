package com.huawei.skillcenter.release;

import java.util.List;
import java.util.Optional;

/** Persistence port for immutable release context and controlled status transitions. */
public interface ReleaseRecordRepository {
    List<ReleaseRecord> findAll(String skillId, String version,
                                ReleaseEnvironment environment, ReleaseStatus status);

    Optional<ReleaseRecord> find(String releaseId);

    Optional<ReleaseRecord> findByIdempotencyKey(String idempotencyKey);

    Optional<ReleaseRecord> findActiveBusinessKey(String skillId, String version,
                                                  ReleaseEnvironment environment);

    ReleaseRecord create(ReleaseRecord value);

    ReleaseRecord replace(ReleaseRecord value);
}
