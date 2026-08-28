package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationDirectoryStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-25T06:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void acceptedSnapshotSurvivesRestartAndIsActiveWithinAge() {
        Path statePath = tempDir.resolve("organization-directory.json");
        OrganizationDirectoryStore first = store(statePath, NOW);

        first.accept(snapshot("rev-1", NOW.plusSeconds(1)), NOW.plusSeconds(1));

        OrganizationDirectoryStore restarted = store(statePath, NOW.plusSeconds(2));
        assertEquals("ACTIVE", restarted.state().status());
        assertTrue(restarted.activeSnapshot(NOW.plusSeconds(10), 900).isPresent());
        assertFalse(restarted.activeSnapshot(NOW.plusSeconds(902), 900).isPresent());
    }

    @Test
    void identicalRevisionAndContentIsIdempotentButConflictsAreRejected() {
        OrganizationDirectoryStore store = store(tempDir.resolve("state.json"), NOW);
        OrganizationDirectorySnapshot first = snapshot("rev-1", NOW.plusSeconds(1));

        store.accept(first, NOW.plusSeconds(1));
        store.accept(first, NOW.plusSeconds(2));

        assertThrows(OrganizationDirectoryRevisionConflictException.class,
                () -> store.accept(snapshot("rev-1", NOW.plusSeconds(3), "bob"), NOW.plusSeconds(3)));
        assertThrows(OrganizationDirectoryRevisionConflictException.class,
                () -> store.accept(snapshot("rev-0", NOW), NOW.plusSeconds(3)));
    }

    @Test
    void failurePreservesSnapshotButRemovesAuthorizationEligibility() {
        OrganizationDirectoryStore store = store(tempDir.resolve("state.json"), NOW);
        store.accept(snapshot("rev-1", NOW.plusSeconds(1)), NOW.plusSeconds(1));

        store.markFailure("DIRECTORY_TIMEOUT", NOW.plusSeconds(2));

        assertEquals("FAILED", store.state().status());
        assertTrue(store.state().snapshot().isPresent());
        assertFalse(store.activeSnapshot(NOW.plusSeconds(2), 900).isPresent());
        assertEquals("DIRECTORY_TIMEOUT", store.state().reasonCode());
    }

    @Test
    void futureAndNonMonotonicFetchedAtAreNotAccepted() {
        OrganizationDirectoryStore store = store(tempDir.resolve("state.json"), NOW);

        assertThrows(IllegalArgumentException.class,
                () -> store.accept(snapshot("rev-1", NOW.plusSeconds(100)), NOW));
        store.accept(snapshot("rev-1", NOW.plusSeconds(1)), NOW.plusSeconds(1));
        assertThrows(OrganizationDirectoryRevisionConflictException.class,
                () -> store.accept(snapshot("rev-2", NOW), NOW.plusSeconds(2)));
    }

    private OrganizationDirectoryStore store(Path path, Instant now) {
        return new OrganizationDirectoryStore(path, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private OrganizationDirectorySnapshot snapshot(String revision, Instant fetchedAt) {
        return snapshot(revision, fetchedAt, "alice");
    }

    private OrganizationDirectorySnapshot snapshot(String revision, Instant fetchedAt, String member) {
        return new OrganizationDirectorySnapshot("organization-directory.v1", "corp-directory", revision, fetchedAt,
                List.of(new OrganizationDirectorySnapshot.Team("team-a", "A", "active", List.of(member))));
    }
}
