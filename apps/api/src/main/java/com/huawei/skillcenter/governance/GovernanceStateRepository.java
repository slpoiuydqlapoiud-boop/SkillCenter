package com.huawei.skillcenter.governance;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Persistence port for the governance aggregate that owns versions, reviews and audit state. */
public interface GovernanceStateRepository {
    Optional<GovernanceState> load();

    GovernanceState loadOrSeed(Supplier<GovernanceSnapshot> seed);

    GovernanceState replace(long expectedRevision, GovernanceSnapshot snapshot);

    record GovernanceState(long revision, GovernanceSnapshot snapshot) {
        public GovernanceState {
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (snapshot == null) {
                throw new IllegalArgumentException("snapshot must not be null");
            }
        }
    }
}
