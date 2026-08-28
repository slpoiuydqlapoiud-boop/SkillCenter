package com.huawei.skillcenter.governance;

import java.time.Instant;

/** Admin-safe status projection; it never contains members, credentials or response bodies. */
public record OrganizationDirectoryStatus(String status, String source, String revision, String reasonCode,
                                          Instant fetchedAt, Instant lastAttemptAt, int teamCount,
                                          int memberCount, boolean stale) {
    public static OrganizationDirectoryStatus local() {
        return new OrganizationDirectoryStatus("LOCAL", "", "", "DIRECTORY_LOCAL_MODE", null, null, 0, 0, false);
    }

    public static OrganizationDirectoryStatus from(OrganizationDirectoryState state, Instant now, long maxAgeSeconds) {
        if (state == null) return new OrganizationDirectoryStatus("FAILED", "", "", "DIRECTORY_STATE_INVALID",
                null, now, 0, 0, false);
        boolean stale = "ACTIVE".equals(state.status()) && !state.isActiveAt(now, maxAgeSeconds);
        String status = stale ? "STALE" : state.status();
        String reason = stale ? "DIRECTORY_SNAPSHOT_STALE" : state.reasonCode();
        return new OrganizationDirectoryStatus(status, state.source(), state.revision(), reason,
                state.fetchedAt(), state.lastAttemptAt(), state.teamCount(), state.memberCount(), stale);
    }
}
