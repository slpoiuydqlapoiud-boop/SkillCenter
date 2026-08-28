package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationDirectorySyncServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T06:00:05Z");

    @TempDir
    Path tempDir;
    private GovernanceStore governanceStore;

    @Test
    void localModeDoesNotCallClientAndReportsLocalStatus() {
        OrganizationDirectoryProperties properties = new OrganizationDirectoryProperties();
        AtomicInteger calls = new AtomicInteger();
        OrganizationDirectorySyncService service = service(properties, () -> {
            calls.incrementAndGet();
            return snapshot("rev-1", NOW);
        });

        OrganizationDirectoryStatus status = service.sync(new Actor("admin", "admin"), "req-local");

        assertEquals("LOCAL", status.status());
        assertEquals(0, calls.get());
    }

    @Test
    void successfulHttpSyncPublishesSafeStatusAndAudit() {
        OrganizationDirectoryProperties properties = httpProperties();
        OrganizationDirectorySyncService service = service(properties, () -> snapshot("rev-1", NOW));

        OrganizationDirectoryStatus status = service.sync(new Actor("admin", "admin"), "req-1");

        assertEquals("ACTIVE", status.status());
        assertEquals("corp-directory", status.source());
        assertEquals("rev-1", status.revision());
        assertEquals(1, status.teamCount());
        AuditEvent audit = governanceStore().snapshot().audits().get(0);
        assertEquals("ORGANIZATION_DIRECTORY_SYNCED", audit.action());
        assertEquals(Map.of("source", "corp-directory", "revision", "rev-1", "teamCount", "1",
                "memberCount", "1", "status", "ACTIVE"), audit.metadata());
    }

    @Test
    void failedFetchChangesStatusButDoesNotExposeOrReplaceSnapshot() {
        OrganizationDirectoryProperties properties = httpProperties();
        OrganizationDirectorySyncService service = service(properties,
                () -> { throw new OrganizationDirectoryUnavailableException("DIRECTORY_TIMEOUT"); });

        OrganizationDirectoryUnavailableException exception = assertThrows(
                OrganizationDirectoryUnavailableException.class,
                () -> service.sync(new Actor("admin", "admin"), "req-fail"));

        assertEquals("DIRECTORY_TIMEOUT", exception.reasonCode());
        assertEquals("FAILED", service.status().status());
        assertTrue(service.status().source().isBlank());
        assertEquals("ORGANIZATION_DIRECTORY_SYNC_FAILED", governanceStore().snapshot().audits().get(0).action());
    }

    @Test
    void staleStatusCannotBeReportedAsActive() {
        OrganizationDirectoryProperties properties = httpProperties();
        properties.setMaxAgeSeconds(30);
        OrganizationDirectorySyncService service = service(properties, () -> snapshot("rev-1", NOW.minusSeconds(31)));

        service.sync(new Actor("admin", "admin"), "req-stale");

        assertEquals("STALE", service.status().status());
        assertEquals("DIRECTORY_SNAPSHOT_STALE", service.status().reasonCode());
    }

    private OrganizationDirectorySyncService service(OrganizationDirectoryProperties properties,
                                                      OrganizationDirectoryClient client) {
        governanceStore = new GovernanceStore(tempDir.resolve("governance-state-" + System.nanoTime() + ".json"), List.of());
        return new OrganizationDirectorySyncService(properties,
                new OrganizationDirectoryStore(tempDir.resolve("directory-state.json"),
                        new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC)),
                client, governanceStore, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GovernanceStore governanceStore() {
        return governanceStore;
    }

    private OrganizationDirectoryProperties httpProperties() {
        OrganizationDirectoryProperties properties = new OrganizationDirectoryProperties();
        properties.setMode("http");
        properties.setEndpoint("https://directory.example.test/snapshot");
        properties.setCredentialRef("secret://env/DIRECTORY_TOKEN");
        properties.validate();
        return properties;
    }

    private OrganizationDirectorySnapshot snapshot(String revision, Instant fetchedAt) {
        return new OrganizationDirectorySnapshot("organization-directory.v1", "corp-directory", revision, fetchedAt,
                List.of(new OrganizationDirectorySnapshot.Team("team-a", "A", "active", List.of("alice"))));
    }
}
