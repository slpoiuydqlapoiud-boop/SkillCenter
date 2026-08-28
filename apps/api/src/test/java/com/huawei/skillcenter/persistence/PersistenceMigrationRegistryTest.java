package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceMigrationRegistryTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T08:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void throwsStableErrorWhenNoMigrationPathExists() throws Exception {
        Path artifactPath = writeArtifact("{\"legacy\":true}");
        PersistenceArtifactDescriptor descriptor = descriptor("governance-state", 2, artifactPath, true);

        assertThatThrownBy(() -> registry(List.of(), requestId("req-unsupported"))
                .ensureCurrent(List.of(descriptor)))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_MIGRATION_UNSUPPORTED");
    }

    @Test
    void appliesMigrationOnceAndPersistsOnlyAllowListedJournalMetadata() throws Exception {
        Path artifactPath = writeArtifact("{\"legacy\":true,\"secret\":\"payload\"}");
        AtomicInteger invocations = new AtomicInteger();
        PersistenceMigration migration = new TestMigration("governance-state", 1, 2, invocations,
                path -> Files.writeString(path, "{\"migrated\":true}", StandardCharsets.UTF_8));
        PersistenceArtifactDescriptor descriptor = descriptor("governance-state", 2, artifactPath, true);

        PersistenceMigrationRegistry registry = registry(List.of(migration), requestId("req-1"));

        List<PersistenceArtifactStatus> firstRun = registry.ensureCurrent(List.of(descriptor));
        List<PersistenceArtifactStatus> secondRun = registry.ensureCurrent(List.of(descriptor));

        assertThat(firstRun).singleElement().satisfies(status -> {
            assertThat(status.state()).isEqualTo(PersistenceArtifactState.READY);
            assertThat(status.observedVersion()).isEqualTo(2);
        });
        assertThat(secondRun).singleElement().satisfies(status -> {
            assertThat(status.state()).isEqualTo(PersistenceArtifactState.READY);
            assertThat(status.observedVersion()).isEqualTo(2);
        });
        assertThat(invocations).hasValue(1);
        assertThat(Files.readString(artifactPath, StandardCharsets.UTF_8)).isEqualTo("{\"migrated\":true}");

        JsonNode journal = OBJECT_MAPPER.readTree(journalPath().toFile());
        assertThat(journal.isArray()).isTrue();
        assertThat(journal).hasSize(1);
        JsonNode entry = journal.get(0);
        assertThat(fieldNames(entry)).containsExactlyInAnyOrder(
                "artifactId",
                "fromVersion",
                "toVersion",
                "executedAt",
                "result",
                "requestId");
        assertThat(entry.path("artifactId").asText()).isEqualTo("governance-state");
        assertThat(entry.path("fromVersion").asInt()).isEqualTo(1);
        assertThat(entry.path("toVersion").asInt()).isEqualTo(2);
        assertThat(entry.path("result").asText()).isEqualTo("SUCCESS");
        assertThat(entry.path("requestId").asText()).isEqualTo("req-1");
        assertThat(journal.toString()).doesNotContain("payload");
    }

    @Test
    void failedMigrationPreservesOriginalBytes() throws Exception {
        String original = "{\"legacy\":true}";
        Path artifactPath = writeArtifact(original);
        PersistenceMigration migration = new TestMigration("governance-state", 1, 2, new AtomicInteger(),
                path -> {
                    Files.writeString(path, "{\"migrated\":true}", StandardCharsets.UTF_8);
                    throw new IOException("boom with payload " + original);
                });
        PersistenceArtifactDescriptor descriptor = descriptor("governance-state", 2, artifactPath, true);

        assertThatThrownBy(() -> registry(List.of(migration), requestId("req-failed"))
                .ensureCurrent(List.of(descriptor)))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_MIGRATION_FAILED");

        assertThat(Files.readString(artifactPath, StandardCharsets.UTF_8)).isEqualTo(original);
        assertThat(Files.exists(journalPath())).isFalse();
    }

    @Test
    void recoversAppliedVersionFromJournalAcrossRestart() throws Exception {
        Path artifactPath = writeArtifact("{\"legacy\":true}");
        AtomicInteger firstInvocations = new AtomicInteger();
        PersistenceMigration firstMigration = new TestMigration("governance-state", 1, 2, firstInvocations,
                path -> Files.writeString(path, "{\"migrated\":true}", StandardCharsets.UTF_8));
        PersistenceArtifactDescriptor descriptor = descriptor("governance-state", 2, artifactPath, true);

        registry(List.of(firstMigration), requestId("req-first")).ensureCurrent(List.of(descriptor));

        AtomicInteger secondInvocations = new AtomicInteger();
        PersistenceMigration secondMigration = new TestMigration("governance-state", 1, 2, secondInvocations,
                path -> Files.writeString(path, "{\"shouldNotRun\":true}", StandardCharsets.UTF_8));

        List<PersistenceArtifactStatus> statuses = registry(List.of(secondMigration), requestId("req-second"))
                .ensureCurrent(List.of(descriptor));

        assertThat(firstInvocations).hasValue(1);
        assertThat(secondInvocations).hasValue(0);
        assertThat(statuses).singleElement().satisfies(status -> {
            assertThat(status.state()).isEqualTo(PersistenceArtifactState.READY);
            assertThat(status.observedVersion()).isEqualTo(2);
        });
        assertThat(Files.readString(artifactPath, StandardCharsets.UTF_8)).isEqualTo("{\"migrated\":true}");
    }

    @Test
    void rejectsSymlinkAncestorBeforeMigrationRunsOrJournalWrites() throws Exception {
        Path realRoot = tempDir.resolve("real-root");
        Path linkedRoot = createSymlinkOrSkip(tempDir.resolve("linked-root"), realRoot);
        Path realArtifactPath = realRoot.resolve("data/governance/state.json");
        Files.createDirectories(realArtifactPath.getParent());
        Files.writeString(realArtifactPath, "{\"legacy\":true}", StandardCharsets.UTF_8);
        AtomicInteger invocations = new AtomicInteger();
        PersistenceMigration migration = new TestMigration("governance-state", 1, 2, invocations,
                path -> Files.writeString(path, "{\"migrated\":true}", StandardCharsets.UTF_8));

        assertThatThrownBy(() -> registry(List.of(migration), requestId("req-symlink"))
                .ensureCurrent(List.of(descriptor(
                        "governance-state",
                        2,
                        linkedRoot.resolve("data/governance/state.json"),
                        true))))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");

        assertThat(invocations).hasValue(0);
        assertThat(Files.readString(realArtifactPath, StandardCharsets.UTF_8)).isEqualTo("{\"legacy\":true}");
        assertThat(Files.exists(journalPath())).isFalse();
    }

    @Test
    void replacesPopulatedDirectoryDuringSuccessfulMigration() throws Exception {
        Path artifactPath = writeDirectoryArtifact(
                "old.txt", "legacy",
                "nested/original.txt", "keep");
        AtomicInteger invocations = new AtomicInteger();
        PersistenceMigration migration = new TestMigration("packages", 1, 2, invocations, path -> {
            deleteTree(path);
            Files.createDirectories(path.resolve("nested"));
            Files.writeString(path.resolve("nested/updated.txt"), "new-content", StandardCharsets.UTF_8);
            Files.writeString(path.resolve("fresh.txt"), "fresh", StandardCharsets.UTF_8);
        });

        List<PersistenceArtifactStatus> statuses = registry(List.of(migration), requestId("req-dir-success"))
                .ensureCurrent(List.of(directoryDescriptor(2, artifactPath, false)));

        assertThat(invocations).hasValue(1);
        assertThat(statuses).singleElement().satisfies(status -> {
            assertThat(status.state()).isEqualTo(PersistenceArtifactState.READY);
            assertThat(status.observedVersion()).isEqualTo(2);
        });
        assertThat(Files.exists(artifactPath.resolve("old.txt"))).isFalse();
        assertThat(Files.exists(artifactPath.resolve("nested/original.txt"))).isFalse();
        assertThat(Files.readString(artifactPath.resolve("nested/updated.txt"), StandardCharsets.UTF_8))
                .isEqualTo("new-content");
        assertThat(Files.readString(artifactPath.resolve("fresh.txt"), StandardCharsets.UTF_8)).isEqualTo("fresh");
    }

    @Test
    void failedDirectoryMigrationLeavesOriginalTreeUnchanged() throws Exception {
        Path artifactPath = writeDirectoryArtifact(
                "old.txt", "legacy",
                "nested/original.txt", "keep");
        AtomicInteger invocations = new AtomicInteger();
        PersistenceMigration migration = new TestMigration("packages", 1, 2, invocations, path -> {
            deleteTree(path);
            Files.createDirectories(path.resolve("nested"));
            Files.writeString(path.resolve("nested/updated.txt"), "new-content", StandardCharsets.UTF_8);
            throw new IOException("directory migration failed");
        });

        assertThatThrownBy(() -> registry(List.of(migration), requestId("req-dir-failed"))
                .ensureCurrent(List.of(directoryDescriptor(2, artifactPath, false))))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_MIGRATION_FAILED");

        assertThat(invocations).hasValue(1);
        assertThat(Files.readString(artifactPath.resolve("old.txt"), StandardCharsets.UTF_8)).isEqualTo("legacy");
        assertThat(Files.readString(artifactPath.resolve("nested/original.txt"), StandardCharsets.UTF_8))
                .isEqualTo("keep");
        assertThat(Files.exists(artifactPath.resolve("nested/updated.txt"))).isFalse();
        assertThat(Files.exists(journalPath())).isFalse();
    }

    @Test
    void rejectsSymlinkDirectoryEncounteredWhileCopyingDirectoryArtifact() throws Exception {
        Path artifactPath = tempDir.resolve("data/packages");
        Path sharedDir = tempDir.resolve("shared");
        Files.createDirectories(artifactPath);
        Files.createDirectories(sharedDir);
        Files.writeString(sharedDir.resolve("secret.txt"), "payload", StandardCharsets.UTF_8);
        createSymlinkOrSkip(artifactPath.resolve("linked-dir"), sharedDir);
        AtomicInteger invocations = new AtomicInteger();
        PersistenceMigration migration = new TestMigration("packages", 1, 2, invocations,
                path -> Files.writeString(path.resolve("ignored.txt"), "ignored", StandardCharsets.UTF_8));

        assertThatThrownBy(() -> registry(List.of(migration), requestId("req-dir-symlink"))
                .ensureCurrent(List.of(directoryDescriptor(2, artifactPath, false))))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");

        assertThat(invocations).hasValue(0);
        assertThat(Files.exists(journalPath())).isFalse();
    }

    @Test
    void restoresOriginalDirectoryWhenReplacementMoveFailsAfterBackupStep() throws Exception {
        Path artifactPath = writeDirectoryArtifact(
                "old.txt", "legacy",
                "nested/original.txt", "keep");
        AtomicInteger invocations = new AtomicInteger();
        PersistenceMigration migration = new TestMigration("packages", 1, 2, invocations, path -> {
            deleteTree(path);
            Files.createDirectories(path.resolve("nested"));
            Files.writeString(path.resolve("nested/updated.txt"), "new-content", StandardCharsets.UTF_8);
            Files.writeString(path.resolve("fresh.txt"), "fresh", StandardCharsets.UTF_8);
        });
        AtomicInteger moves = new AtomicInteger();

        assertThatThrownBy(() -> registryWithMove(List.of(migration), requestId("req-dir-restore"), (source, target, replaceExisting) -> {
                    int moveNumber = moves.incrementAndGet();
                    if (moveNumber == 2) {
                        throw new IOException("injected move failure");
                    }
                    Files.move(source, target);
                })
                .ensureCurrent(List.of(directoryDescriptor(2, artifactPath, false))))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_MIGRATION_FAILED");

        assertThat(invocations).hasValue(1);
        assertThat(moves).hasValue(3);
        assertThat(Files.readString(artifactPath.resolve("old.txt"), StandardCharsets.UTF_8)).isEqualTo("legacy");
        assertThat(Files.readString(artifactPath.resolve("nested/original.txt"), StandardCharsets.UTF_8))
                .isEqualTo("keep");
        assertThat(Files.exists(artifactPath.resolve("nested/updated.txt"))).isFalse();
        assertThat(Files.exists(artifactPath.resolve("fresh.txt"))).isFalse();
        assertThat(Files.exists(artifactPath.resolveSibling("packages.migration-backup"))).isFalse();
        assertThat(Files.exists(journalPath())).isFalse();
    }

    private PersistenceMigrationRegistry registry(List<PersistenceMigration> migrations, Supplier<String> requestIdSupplier) {
        return new PersistenceMigrationRegistry(
                properties(),
                tempDir,
                new PersistenceIntegrityService(tempDir),
                migrations,
                CLOCK,
                requestIdSupplier);
    }

    private PersistenceMigrationRegistry registryWithMove(List<PersistenceMigration> migrations,
                                                          Supplier<String> requestIdSupplier,
                                                          PersistenceMigrationRegistry.MoveOperation moveOperation) {
        return new PersistenceMigrationRegistry(
                properties(),
                tempDir,
                new PersistenceIntegrityService(tempDir),
                migrations,
                CLOCK,
                requestIdSupplier,
                moveOperation);
    }

    private PersistenceControlProperties properties() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setBackend("json");
        properties.setControlStorage("control");
        properties.setSnapshotStorage("snapshots");
        properties.setStartupMode("fail-closed");
        properties.setManifestRetention(10);
        return properties;
    }

    private Supplier<String> requestId(String requestId) {
        return () -> requestId;
    }

    private Path writeArtifact(String payload) throws Exception {
        Path artifactPath = tempDir.resolve("data/governance/state.json");
        Files.createDirectories(artifactPath.getParent());
        Files.writeString(artifactPath, payload, StandardCharsets.UTF_8);
        return artifactPath;
    }

    private PersistenceArtifactDescriptor descriptor(String artifactId, int schemaVersion, Path path, boolean critical) {
        return new PersistenceArtifactDescriptor(artifactId, PersistenceArtifactKind.FILE, schemaVersion, path, critical, true);
    }

    private PersistenceArtifactDescriptor directoryDescriptor(int schemaVersion, Path path, boolean critical) {
        return new PersistenceArtifactDescriptor("packages", PersistenceArtifactKind.DIRECTORY, schemaVersion, path, critical, true);
    }

    private Path journalPath() {
        return tempDir.resolve("control/migration-journal.json");
    }

    private Set<String> fieldNames(JsonNode entry) {
        return entry.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }

    private Path writeDirectoryArtifact(String firstRelativePath,
                                        String firstContent,
                                        String secondRelativePath,
                                        String secondContent) throws Exception {
        Path artifactPath = tempDir.resolve("data/packages");
        Files.createDirectories(artifactPath.resolve("nested"));
        Files.writeString(artifactPath.resolve(firstRelativePath), firstContent, StandardCharsets.UTF_8);
        Files.writeString(artifactPath.resolve(secondRelativePath), secondContent, StandardCharsets.UTF_8);
        return artifactPath;
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path current : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!current.equals(root)) {
                    Files.deleteIfExists(current);
                }
            }
        }
    }

    private Path createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Path parent = link.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getClass().getSimpleName());
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }
        throw new IllegalStateException("symbolic link assumption should have aborted test");
    }

    private record TestMigration(
            String artifactId,
            int fromVersion,
            int toVersion,
            AtomicInteger invocations,
            MigrationBody body) implements PersistenceMigration {

        @Override
        public void apply(Path artifactPath) throws IOException {
            invocations.incrementAndGet();
            body.apply(artifactPath);
        }
    }

    @FunctionalInterface
    private interface MigrationBody {
        void apply(Path path) throws IOException;
    }
}
