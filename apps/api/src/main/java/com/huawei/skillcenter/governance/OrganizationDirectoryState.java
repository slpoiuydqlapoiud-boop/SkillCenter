package com.huawei.skillcenter.governance;

import java.time.Instant;
import java.util.Optional;

/** Persisted, safe control-plane status for the organization snapshot. */
public record OrganizationDirectoryState(String status, Optional<OrganizationDirectorySnapshot> snapshot,
                                         String source, String revision, String contentHash,
                                         Instant fetchedAt, Instant lastAttemptAt, String reasonCode,
                                         int teamCount, int memberCount) {
    public OrganizationDirectoryState {
        status = status == null || status.isBlank() ? "NOT_CONFIGURED" : status.trim();
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        source = source == null ? "" : source.trim();
        revision = revision == null ? "" : revision.trim();
        contentHash = contentHash == null ? "" : contentHash.trim();
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        if (teamCount < 0 || memberCount < 0) throw new IllegalArgumentException("directory counts are invalid");
    }

    public static OrganizationDirectoryState empty() {
        return new OrganizationDirectoryState("NOT_CONFIGURED", Optional.empty(), "", "", "",
                null, null, "DIRECTORY_NOT_CONFIGURED", 0, 0);
    }

    public static OrganizationDirectoryState active(OrganizationDirectorySnapshot snapshot, Instant attemptedAt) {
        return new OrganizationDirectoryState("ACTIVE", Optional.of(snapshot), snapshot.source(), snapshot.revision(),
                snapshot.contentHash(), snapshot.fetchedAt(), attemptedAt, "", snapshot.teams().size(),
                snapshot.teams().stream().mapToInt(team -> team.memberUserIds().size()).sum());
    }

    public OrganizationDirectoryState failed(String reason, Instant attemptedAt) {
        return new OrganizationDirectoryState("FAILED", snapshot, source, revision, contentHash, fetchedAt,
                attemptedAt, reason, teamCount, memberCount);
    }

    public boolean isActiveAt(Instant now, long maxAgeSeconds) {
        if (!"ACTIVE".equals(status) || snapshot.isEmpty() || fetchedAt == null || now == null
                || now.isBefore(fetchedAt) || maxAgeSeconds < 0) return false;
        return !now.isAfter(fetchedAt.plusSeconds(maxAgeSeconds));
    }
}
