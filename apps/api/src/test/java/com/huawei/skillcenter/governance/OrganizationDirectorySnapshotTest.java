package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganizationDirectorySnapshotTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-08-25T06:00:01Z");

    @Test
    void snapshotSortsAndFreezesTeamsAndMembers() {
        OrganizationDirectorySnapshot snapshot = new OrganizationDirectorySnapshot(
                "organization-directory.v1", "corp-directory", "rev-2", FETCHED_AT,
                List.of(
                        new OrganizationDirectorySnapshot.Team("team-b", "B", "active", List.of("bob", "alice")),
                        new OrganizationDirectorySnapshot.Team("team-a", "A", "active", List.of("carol"))));

        assertEquals(List.of("team-a", "team-b"), snapshot.teams().stream()
                .map(OrganizationDirectorySnapshot.Team::teamId).toList());
        assertEquals(List.of("alice", "bob"), snapshot.teams().get(1).memberUserIds());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.teams().add(new OrganizationDirectorySnapshot.Team("team-c", "C", "active", List.of())));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.teams().get(0).memberUserIds().add("dave"));
    }

    @Test
    void snapshotRejectsDuplicateAndInvalidBoundaries() {
        OrganizationDirectorySnapshot.Team team = new OrganizationDirectorySnapshot.Team(
                "team-a", "A", "active", List.of("alice"));

        assertThrows(IllegalArgumentException.class, () -> new OrganizationDirectorySnapshot(
                "wrong-schema", "corp-directory", "rev-1", FETCHED_AT, List.of(team)));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationDirectorySnapshot(
                "organization-directory.v1", "corp-directory", "rev-1", FETCHED_AT, List.of(team, team)));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationDirectorySnapshot.Team(
                "bad team", "A", "active", List.of("alice")));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationDirectorySnapshot.Team(
                "team-a", "A", "active", List.of("alice", "alice")));
    }

    @Test
    void snapshotRejectsResponseBounds() {
        List<OrganizationDirectorySnapshot.Team> tooManyTeams = java.util.stream.IntStream.range(0, 5001)
                .mapToObj(index -> new OrganizationDirectorySnapshot.Team("team-" + index, "T", "active", List.of()))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> new OrganizationDirectorySnapshot(
                "organization-directory.v1", "corp-directory", "rev-1", FETCHED_AT, tooManyTeams));
        assertThrows(IllegalArgumentException.class, () -> new OrganizationDirectorySnapshot.Team(
                "team-a", "A", "active", java.util.stream.IntStream.range(0, 10001)
                        .mapToObj(index -> "user-" + index).toList()));
    }

    @Test
    void snapshotDigestChangesWhenMembershipChanges() {
        OrganizationDirectorySnapshot first = snapshot(List.of("alice"));
        OrganizationDirectorySnapshot second = snapshot(List.of("bob"));

        assertEquals(64, first.contentHash().length());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.contentHash(), second.contentHash());
    }

    @Test
    void propertiesValidateHttpModeAndBoundedTimeouts() {
        OrganizationDirectoryProperties properties = new OrganizationDirectoryProperties();

        properties.setMode("http");
        properties.setEndpoint("https://directory.example.test/v1/snapshot");
        properties.setCredentialRef("secret://env/DIRECTORY_TOKEN");
        properties.validate();
        assertEquals("http", properties.getMode());

        properties.setConnectTimeoutMs(100);
        properties.setRequestTimeoutMs(100);
        properties.setMaxResponseBytes(4096);
        properties.setMaxAgeSeconds(30);
        properties.validate();

        properties.setEndpoint("http://directory.example.test/v1/snapshot");
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setEndpoint("https://directory.example.test/v1/snapshot?token=secret");
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setCredentialRef("token");
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void localModeNeedsNoRemoteConfiguration() {
        OrganizationDirectoryProperties properties = new OrganizationDirectoryProperties();

        properties.validate();

        assertEquals("local", properties.getMode());
    }

    private OrganizationDirectorySnapshot snapshot(List<String> members) {
        return new OrganizationDirectorySnapshot("organization-directory.v1", "corp-directory", "rev-1",
                FETCHED_AT, List.of(new OrganizationDirectorySnapshot.Team("team-a", "A", "active", members)));
    }
}
