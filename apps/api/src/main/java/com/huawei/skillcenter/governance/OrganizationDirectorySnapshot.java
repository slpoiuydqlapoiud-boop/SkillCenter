package com.huawei.skillcenter.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Bounded, normalized organization facts used by TEAM authorization. */
public record OrganizationDirectorySnapshot(String schemaVersion, String source, String revision,
                                            Instant fetchedAt, List<Team> teams) {
    public static final String SCHEMA_VERSION = "organization-directory.v1";
    public static final int MAX_TEAMS = 5_000;
    public static final int MAX_MEMBERS_PER_TEAM = 10_000;
    public static final int MAX_TOTAL_MEMBERS = 200_000;
    private static final String TEAM_ID = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final String USER_ID = "[A-Za-z0-9][A-Za-z0-9._@:+-]{0,127}";
    private static final String REVISION = "[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}";

    public OrganizationDirectorySnapshot {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("organization directory schema is invalid");
        }
        source = requiredBounded(source, "source", REVISION);
        revision = requiredBounded(revision, "revision", REVISION);
        fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt");
        List<Team> normalizedTeams = teams == null ? List.of() : teams.stream().filter(Objects::nonNull).toList();
        if (normalizedTeams.size() != (teams == null ? 0 : teams.size()) || normalizedTeams.size() > MAX_TEAMS) {
            throw new IllegalArgumentException("organization directory team count is invalid");
        }
        Set<String> teamIds = new TreeSet<>();
        int memberCount = 0;
        for (Team team : normalizedTeams) {
            if (!teamIds.add(team.teamId())) {
                throw new IllegalArgumentException("organization directory contains duplicate team");
            }
            memberCount += team.memberUserIds().size();
        }
        if (memberCount > MAX_TOTAL_MEMBERS) {
            throw new IllegalArgumentException("organization directory member count is invalid");
        }
        teams = normalizedTeams.stream().sorted(java.util.Comparator.comparing(Team::teamId)).toList();
    }

    public String contentHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, schemaVersion);
            update(digest, source);
            update(digest, revision);
            update(digest, fetchedAt.toString());
            for (Team team : teams) {
                update(digest, team.teamId());
                update(digest, team.name());
                update(digest, team.status());
                for (String member : team.memberUserIds()) update(digest, member);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public java.util.Optional<Team> team(String teamId) {
        return teams.stream().filter(team -> team.teamId().equals(teamId)).findFirst();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String requiredBounded(String value, String field, String pattern) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || !normalized.matches(pattern)) {
            throw new IllegalArgumentException("organization directory " + field + " is invalid");
        }
        return normalized;
    }

    public record Team(String teamId, String name, String status, List<String> memberUserIds) {
        public Team {
            teamId = requiredBounded(teamId, "team id", TEAM_ID);
            name = bounded(name, "team name", 256);
            status = requiredBounded(status, "team status", "[A-Za-z][A-Za-z0-9_-]{0,31}").toLowerCase(java.util.Locale.ROOT);
            List<String> members = memberUserIds == null ? List.of() : memberUserIds.stream()
                    .map(value -> requiredBounded(value, "member user id", USER_ID))
                    .sorted().toList();
            if (members.size() > MAX_MEMBERS_PER_TEAM || new TreeSet<>(members).size() != members.size()) {
                throw new IllegalArgumentException("organization directory member list is invalid");
            }
            memberUserIds = List.copyOf(members);
        }

        private static String bounded(String value, String field, int max) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() > max) throw new IllegalArgumentException(field + " is too long");
            return normalized;
        }
    }
}
