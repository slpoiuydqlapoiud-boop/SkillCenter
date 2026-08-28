package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceSnapshotServiceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Clock CLOCK_1 = Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);
    private static final Clock CLOCK_2 = Clock.fixed(Instant.parse("2026-08-24T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void createSnapshotCopiesIncludedArtifactsWritesStableManifestDigestAndStoresWhitelistedMetadata() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"top-secret\",\"beta\":2}");
        writeJson("data/governance/excluded.json", "{\"excluded\":\"payload\"}");
        writeDirectory(Map.of(
                "data/packages/root.txt", "package-root",
                "data/packages/nested/child.txt", "package-child"));

        PersistenceSnapshotService service = service(
                catalog(List.of(
                        definition("packages", PersistenceArtifactKind.DIRECTORY, "data/packages", false, true),
                        definition("optional-metrics", PersistenceArtifactKind.FILE, "data/optional/missing.json", false, true),
                        definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true),
                        definition("excluded-artifact", PersistenceArtifactKind.FILE, "data/governance/excluded.json", false, false))),
                new PersistenceIntegrityService(tempDir),
                CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));

        PersistenceSnapshotManifest manifest = service.createSnapshot();

        assertThat(manifest.snapshotId()).isEqualTo("snapshot-20260824t090000z-0001");
        assertThat(manifest.createdAt()).isEqualTo(Instant.parse("2026-08-24T09:00:00Z"));
        assertThat(manifest.backend()).isEqualTo("json");
        assertThat(manifest.artifactCount()).isEqualTo(3);
        assertThat(manifest.state()).isEqualTo("COMPLETE");
        assertThat(manifest.artifacts()).extracting(PersistenceSnapshotArtifact::artifactId)
                .containsExactly("governance-state", "optional-metrics", "packages");

        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        Path manifestPath = snapshotDir.resolve("manifest.json");
        Path digestPath = snapshotDir.resolve("manifest.sha256");
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        String expectedDigest = sha256(manifestBytes);
        assertThat(manifest.manifestSha256()).isEqualTo(expectedDigest);
        assertThat(Files.readString(digestPath, StandardCharsets.UTF_8)).isEqualTo(expectedDigest);

        PersistenceSnapshotArtifact governanceArtifact = artifact(manifest, "governance-state");
        assertThat(governanceArtifact.relativePath()).isEqualTo("artifacts/governance-state/data");
        assertThat(Files.readString(snapshotDir.resolve(governanceArtifact.relativePath()), StandardCharsets.UTF_8))
                .isEqualTo("{\"alpha\":\"top-secret\",\"beta\":2}");

        PersistenceSnapshotArtifact packagesArtifact = artifact(manifest, "packages");
        assertThat(packagesArtifact.relativePath()).isEqualTo("artifacts/packages/data");
        assertThat(Files.readString(snapshotDir.resolve("artifacts/packages/data/root.txt"), StandardCharsets.UTF_8))
                .isEqualTo("package-root");
        assertThat(Files.readString(snapshotDir.resolve("artifacts/packages/data/nested/child.txt"), StandardCharsets.UTF_8))
                .isEqualTo("package-child");

        PersistenceSnapshotArtifact optionalArtifact = artifact(manifest, "optional-metrics");
        assertThat(optionalArtifact.relativePath()).isEmpty();
        assertThat(optionalArtifact.availability()).isEqualTo("OPTIONAL_MISSING");
        assertThat(optionalArtifact.sizeBytes()).isNull();
        assertThat(optionalArtifact.sha256()).isNull();
        assertThat(optionalArtifact.recordCount()).isNull();
        assertThat(snapshotDir.resolve("artifacts/optional-metrics")).doesNotExist();

        JsonNode storedMetadata = OBJECT_MAPPER.readTree(controlRoot().resolve("snapshots.json").toFile());
        assertThat(storedMetadata.isArray()).isTrue();
        assertThat(storedMetadata).hasSize(1);
        JsonNode storedManifest = storedMetadata.get(0);
        assertThat(fieldNames(storedManifest)).containsExactlyInAnyOrder(
                "snapshotId",
                "createdAt",
                "backend",
                "artifactCount",
                "manifestSha256",
                "state",
                "artifacts");
        assertThat(storedMetadata.toString())
                .doesNotContain("top-secret")
                .doesNotContain("package-root")
                .doesNotContain("data/governance/state.json");
    }

    @Test
    void fileSnapshotCreationRejectsPostgresQualityEvidenceBeforePublication() throws Exception {
        Path source = writeJson("data/quality-evidence.json", "{\"legacy\":true}");
        PersistenceControlProperties properties = properties(10);
        properties.setBackend("postgresql");
        properties.setQualityEvidenceBackend("postgresql");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("quality-evidence", PersistenceArtifactKind.FILE,
                        "data/quality-evidence.json", false, true)), properties);
        PersistenceSnapshotService service = service(
                catalog,
                new PersistenceIntegrityService(tempDir),
                properties,
                CLOCK_1,
                snapshotIds("snapshot-postgres-rejected"));

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(PersistenceControlException.class)
                .hasMessage("PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED");
        assertThat(Files.exists(snapshotRoot())).isFalse();
        assertThat(Files.exists(controlRoot().resolve("snapshots.json"))).isFalse();
        assertThat(Files.readString(source)).isEqualTo("{\"legacy\":true}");
    }

    @Test
    void restorePreflightBlocksExistingJsonQualityEvidenceSnapshotAfterSwitchingToPostgres() throws Exception {
        writeJson("data/quality-evidence.json", "{\"legacy\":true}");
        List<PersistenceArtifactCatalog.Definition> definitions = List.of(
                definition("quality-evidence", PersistenceArtifactKind.FILE,
                        "data/quality-evidence.json", false, true));
        PersistenceArtifactCatalog jsonCatalog = catalog(definitions);
        PersistenceSnapshotManifest manifest = service(
                jsonCatalog,
                new PersistenceIntegrityService(tempDir),
                CLOCK_1,
                snapshotIds("snapshot-json-quality-evidence")).createSnapshot();

        PersistenceControlProperties postgresProperties = properties(10);
        postgresProperties.setBackend("postgresql");
        postgresProperties.setQualityEvidenceBackend("postgresql");
        PersistenceArtifactCatalog postgresCatalog = catalog(definitions, postgresProperties);
        PersistenceSnapshotService postgresService = service(
                postgresCatalog,
                new PersistenceIntegrityService(tempDir),
                postgresProperties,
                CLOCK_1,
                snapshotIds("unused"));

        assertThat(postgresService.restorePreflight(manifest.snapshotId()))
                .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                        PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                .containsExactly("BLOCKED", "PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED");
    }

    @Test
    void createSnapshotRejectsCriticalNonReadyArtifacts() {
        Path artifactPath = tempDir.resolve("data/governance/state.json");
        PersistenceArtifactDescriptor descriptor = new PersistenceArtifactDescriptor(
                "governance-state",
                PersistenceArtifactKind.FILE,
                1,
                artifactPath,
                true,
                true);
        PersistenceArtifactCatalog catalog = catalog(List.of(
                new PersistenceArtifactCatalog.Definition(
                        descriptor.artifactId(),
                        descriptor.kind(),
                        descriptor.schemaVersion(),
                        artifactPath.toString(),
                        descriptor.critical(),
                        descriptor.includeInSnapshot())));

        assertThatThrownBy(() -> service(
                catalog,
                overrideIntegrity(Map.of(
                        artifactPath,
                        status(descriptor, PersistenceArtifactState.MISSING, null, null, null, "PERSISTENCE_ARTIFACT_MISSING"))),
                CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001")).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_ARTIFACT_MISSING");

        assertThatThrownBy(() -> service(
                catalog,
                overrideIntegrity(Map.of(
                        artifactPath,
                        status(descriptor, PersistenceArtifactState.CORRUPTED, null, null, null, "PERSISTENCE_ARTIFACT_CORRUPTED"))),
                CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001")).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");

        assertThatThrownBy(() -> service(
                catalog,
                overrideIntegrity(Map.of(
                        artifactPath,
                        status(descriptor, PersistenceArtifactState.MIGRATION_REQUIRED, null, null, null, "PERSISTENCE_MIGRATION_REQUIRED"))),
                CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001")).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_MIGRATION_REQUIRED");
    }

    @Test
    void createSnapshotRejectsCopyIntegrityMismatchAndDoesNotPublishSnapshot() throws Exception {
        Path artifactPath = writeJson("data/governance/state.json", "{\"alpha\":\"source\"}");
        PersistenceArtifactDescriptor descriptor = new PersistenceArtifactDescriptor(
                "governance-state",
                PersistenceArtifactKind.FILE,
                1,
                artifactPath,
                true,
                true);
        String snapshotId = "snapshot-20260824t090000z-0001";
        Path copiedPath = snapshotRoot().resolve(snapshotId).resolve("artifacts/governance-state/data");

        PersistenceIntegrityService integrity = overrideIntegrity(Map.of(
                artifactPath, readyStatus(descriptor, 18L, repeat("a"), 2L),
                copiedPath, readyStatus(new PersistenceArtifactDescriptor(
                        descriptor.artifactId(),
                        descriptor.kind(),
                        descriptor.schemaVersion(),
                        copiedPath,
                        descriptor.critical(),
                        descriptor.includeInSnapshot()), 18L, repeat("b"), 2L)));

        assertThatThrownBy(() -> service(catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true))),
                integrity,
                CLOCK_1,
                snapshotIds(snapshotId)).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");

        assertThat(service(catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true))),
                new PersistenceIntegrityService(tempDir),
                CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0002")).listSnapshots()).isEmpty();
    }

    @Test
    void createSnapshotRejectsSourcePathOutsideConfiguredRoot() {
        Path outside = tempDir.getParent().resolve("outside-state.json").toAbsolutePath().normalize();

        assertThatThrownBy(() -> service(
                catalog(List.of(new PersistenceArtifactCatalog.Definition(
                        "governance-state",
                        PersistenceArtifactKind.FILE,
                        1,
                        outside.toString(),
                        true,
                        true))),
                new PersistenceIntegrityService(tempDir),
                CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001")).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");
    }

    @Test
    void listSnapshotsIgnoresTemporaryDirectoriesAndSortsNewestFirst() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"state\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));

        service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001")).createSnapshot();
        service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_2,
                snapshotIds("snapshot-20260824t100000z-0002")).createSnapshot();

        Files.createDirectories(snapshotRoot().resolve("snapshot-20260824t100500z-0003.tmp"));
        Files.writeString(snapshotRoot().resolve("snapshot-20260824t100500z-0003.tmp").resolve("partial.txt"),
                "partial",
                StandardCharsets.UTF_8);

        List<PersistenceSnapshotManifest> snapshots = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_2,
                snapshotIds("snapshot-20260824t110000z-0004")).listSnapshots();

        assertThat(snapshots).extracting(PersistenceSnapshotManifest::snapshotId)
                .containsExactly("snapshot-20260824t100000z-0002", "snapshot-20260824t090000z-0001");
        assertThatThrownBy(() -> service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_2,
                snapshotIds("snapshot-20260824t110000z-0004")).getSnapshot("missing"))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_NOT_FOUND");
    }

    @Test
    void createSnapshotRejectsTraversalSnapshotIdFromMetadataBeforeRetentionCleanup() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        Path outsideSnapshotRoot = tempDir.resolve("outside-retention-marker.txt");
        Files.writeString(outsideSnapshotRoot, "must-survive", StandardCharsets.UTF_8);
        PersistenceSnapshotManifest tamperedMetadata = new PersistenceSnapshotManifest(
                "..",
                CLOCK_1.instant(),
                "json",
                0,
                "",
                "COMPLETE",
                List.of());
        Files.createDirectories(controlRoot());
        OBJECT_MAPPER.writeValue(controlRoot().resolve("snapshots.json").toFile(), List.of(tamperedMetadata));

        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));

        assertThatThrownBy(() -> service(catalog, new PersistenceIntegrityService(tempDir), properties(1), CLOCK_2,
                snapshotIds("snapshot-20260824t100000z-0002")).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(outsideSnapshotRoot).exists().hasContent("must-survive");
    }

    @Test
    void getSnapshotAndRestorePreflightRejectDotAndDotDotSnapshotIds() throws Exception {
        PersistenceArtifactCatalog catalog = catalog(List.of());
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));

        for (String invalidSnapshotId : List.of(".", "..")) {
            assertThatThrownBy(() -> service.getSnapshot(invalidSnapshotId))
                    .isInstanceOf(PersistenceControlException.class)
                    .extracting("code")
                    .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
            assertThat(service.restorePreflight(invalidSnapshotId))
                    .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                            PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                    .containsExactly("BLOCKED", "PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    @Test
    void restorePreflightReturnsReadyWithoutMutatingLiveData() throws Exception {
        Path artifactPath = writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        service.createSnapshot();

        String liveBefore = Files.readString(artifactPath, StandardCharsets.UTF_8);
        PersistenceSnapshotService.RestorePreflightResult preflight = service.restorePreflight("snapshot-20260824t090000z-0001");

        assertThat(preflight.status()).isEqualTo("READY");
        assertThat(preflight.reasonCode()).isEmpty();
        assertThat(preflight.artifactCount()).isEqualTo(1);
        assertThat(Files.readString(artifactPath, StandardCharsets.UTF_8)).isEqualTo(liveBefore);
    }

    @Test
    void restorePreflightBlocksIncompleteAndTamperedSnapshotsWithoutMutatingLiveData() throws Exception {
        Path artifactPath = writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        service.createSnapshot();

        Path snapshotDir = snapshotRoot().resolve("snapshot-20260824t090000z-0001");
        Files.writeString(snapshotDir.resolve("artifacts/governance-state/data"), "{\"alpha\":\"tampered\"}",
                StandardCharsets.UTF_8);

        PersistenceSnapshotService.RestorePreflightResult tampered = service.restorePreflight("snapshot-20260824t090000z-0001");
        assertThat(tampered.status()).isEqualTo("BLOCKED");
        assertThat(tampered.reasonCode()).isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(Files.readString(artifactPath, StandardCharsets.UTF_8)).isEqualTo("{\"alpha\":\"live\"}");

        Path incompleteDir = snapshotRoot().resolve("snapshot-20260824t091500z-0002");
        Files.createDirectories(incompleteDir);
        Files.writeString(incompleteDir.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);

        PersistenceSnapshotService.RestorePreflightResult incomplete = service.restorePreflight("snapshot-20260824t091500z-0002");
        assertThat(incomplete.status()).isEqualTo("BLOCKED");
        assertThat(incomplete.reasonCode()).isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(Files.readString(artifactPath, StandardCharsets.UTF_8)).isEqualTo("{\"alpha\":\"live\"}");
    }

    @Test
    void restorePreflightBlocksRelativePathEscapes() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();

        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        JsonNode tree = OBJECT_MAPPER.readTree(snapshotDir.resolve("manifest.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.withArray("artifacts").get(0))
                .put("relativePath", "../escape");
        byte[] mutatedBytes = OBJECT_MAPPER.writeValueAsBytes(tree);
        Files.write(snapshotDir.resolve("manifest.json"), mutatedBytes);
        Files.writeString(snapshotDir.resolve("manifest.sha256"), sha256(mutatedBytes), StandardCharsets.UTF_8);

        PersistenceSnapshotService.RestorePreflightResult preflight = service.restorePreflight(manifest.snapshotId());

        assertThat(preflight.status()).isEqualTo("BLOCKED");
        assertThat(preflight.reasonCode()).isEqualTo("PERSISTENCE_RESTORE_BLOCKED");
    }

    @Test
    void restorePreflightRejectsDuplicateArtifactIds() throws Exception {
        writeJson("data/one.json", "{\"same\":true}");
        writeJson("data/two.json", "{\"same\":true}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("one", PersistenceArtifactKind.FILE, "data/one.json", true, true),
                definition("two", PersistenceArtifactKind.FILE, "data/two.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();

        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        JsonNode tree = OBJECT_MAPPER.readTree(snapshotDir.resolve("manifest.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.withArray("artifacts").get(1))
                .put("artifactId", "one");
        writeManifest(snapshotDir, tree);

        PersistenceSnapshotService.RestorePreflightResult preflight = service.restorePreflight(manifest.snapshotId());

        assertThat(preflight.status()).isEqualTo("BLOCKED");
        assertThat(preflight.reasonCode()).isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
    }

    @Test
    void restorePreflightRejectsMissingExpectedArtifactIdEvenWhenArtifactCountMatches() throws Exception {
        writeJson("data/one.json", "{\"same\":true}");
        writeJson("data/two.json", "{\"same\":true}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("one", PersistenceArtifactKind.FILE, "data/one.json", true, true),
                definition("two", PersistenceArtifactKind.FILE, "data/two.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();

        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        JsonNode tree = OBJECT_MAPPER.readTree(snapshotDir.resolve("manifest.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.withArray("artifacts").get(1))
                .put("artifactId", "one");
        writeManifest(snapshotDir, tree);

        PersistenceSnapshotService.RestorePreflightResult preflight = service.restorePreflight(manifest.snapshotId());

        assertThat(preflight.status()).isEqualTo("BLOCKED");
        assertThat(preflight.reasonCode()).isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
    }

    @Test
    void restorePreflightBlocksBlankRelativePathForCriticalArtifact() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();

        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        JsonNode tree = OBJECT_MAPPER.readTree(snapshotDir.resolve("manifest.json").toFile());
        com.fasterxml.jackson.databind.node.ObjectNode artifactNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) tree.withArray("artifacts").get(0);
        artifactNode.put("relativePath", "");
        artifactNode.putNull("sizeBytes");
        artifactNode.putNull("sha256");
        artifactNode.putNull("recordCount");
        writeManifest(snapshotDir, tree);

        PersistenceSnapshotService.RestorePreflightResult preflight = service.restorePreflight(manifest.snapshotId());

        assertThat(preflight.status()).isEqualTo("BLOCKED");
        assertThat(preflight.reasonCode()).isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
    }

    @Test
    void restorePreflightAllowsBlankRelativePathOnlyForNonCriticalOptionalArtifact() throws Exception {
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("optional-state", PersistenceArtifactKind.FILE, "data/optional.json", false, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));

        PersistenceSnapshotManifest manifest = service.createSnapshot();

        assertThat(service.restorePreflight(manifest.snapshotId()))
                .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                        PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                .containsExactly("READY", "");
    }

    @Test
    void restorePreflightRejectsTamperedBackendStateAndArtifactCount() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();
        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        Path manifestPath = snapshotDir.resolve("manifest.json");
        byte[] originalBytes = Files.readAllBytes(manifestPath);

        for (String field : List.of("backend", "state")) {
            JsonNode tree = OBJECT_MAPPER.readTree(originalBytes);
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put(field,
                    "backend".equals(field) ? "tampered" : "IN_PROGRESS");
            writeManifest(snapshotDir, tree);
            assertThat(service.restorePreflight(manifest.snapshotId()).reasonCode())
                    .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        }

        JsonNode tree = OBJECT_MAPPER.readTree(originalBytes);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("artifactCount", 2);
        writeManifest(snapshotDir, tree);

        assertThat(service.restorePreflight(manifest.snapshotId()))
                .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                        PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                .containsExactly("BLOCKED", "PERSISTENCE_SNAPSHOT_INVALID");
    }

    @Test
    void createSnapshotAppliesRetentionWithoutDeletingNewestCompleteSnapshot() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));

        service(catalog, new PersistenceIntegrityService(tempDir), properties(1), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001")).createSnapshot();
        service(catalog, new PersistenceIntegrityService(tempDir), properties(1), CLOCK_2,
                snapshotIds("snapshot-20260824t100000z-0002")).createSnapshot();

        List<PersistenceSnapshotManifest> manifests = service(catalog, new PersistenceIntegrityService(tempDir), properties(1),
                CLOCK_2, snapshotIds("snapshot-20260824t110000z-0003")).listSnapshots();

        assertThat(manifests).extracting(PersistenceSnapshotManifest::snapshotId)
                .containsExactly("snapshot-20260824t100000z-0002");
        assertThat(snapshotRoot().resolve("snapshot-20260824t090000z-0001")).doesNotExist();
        assertThat(snapshotRoot().resolve("snapshot-20260824t100000z-0002")).exists();
    }

    @Test
    void retentionRejectsMetadataTargetThatIsARegularFileBeforeDeletingIt() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), properties(1), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001", "snapshot-20260824t100000z-0002"));
        service.createSnapshot();

        Path tamperedTarget = snapshotRoot().resolve("snapshot-20260824t090000z-0001");
        deleteTree(tamperedTarget);
        Files.writeString(tamperedTarget, "operator-marker", StandardCharsets.UTF_8);

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(tamperedTarget).isRegularFile().hasContent("operator-marker");
    }

    @Test
    void retentionDoesNotDeleteReplacementRegularFileAfterDeletionTargetRevalidation() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), properties(1), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001", "snapshot-20260824t100000z-0002"),
                snapshotDir -> {
                    deleteTree(snapshotDir);
                    Files.writeString(snapshotDir, "operator-marker", StandardCharsets.UTF_8);
                });
        service.createSnapshot();

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(snapshotRoot().resolve("snapshot-20260824t090000z-0001"))
                .isRegularFile()
                .hasContent("operator-marker");
    }

    @Test
    void retentionRejectsMetadataTargetDirectoryWithoutCompleteManifestBeforeDeletingIt() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), properties(1), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001", "snapshot-20260824t100000z-0002"));
        service.createSnapshot();

        Path tamperedTarget = snapshotRoot().resolve("snapshot-20260824t090000z-0001");
        deleteTree(tamperedTarget);
        Files.createDirectories(tamperedTarget);
        Files.writeString(tamperedTarget.resolve("marker.txt"), "must-survive", StandardCharsets.UTF_8);

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(tamperedTarget.resolve("marker.txt")).hasContent("must-survive");
    }

    @Test
    void retentionRejectsMetadataTargetWithNonCompleteManifestBeforeDeletingIt() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), properties(1), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001", "snapshot-20260824t100000z-0002"));
        service.createSnapshot();

        Path tamperedTarget = snapshotRoot().resolve("snapshot-20260824t090000z-0001");
        JsonNode tree = OBJECT_MAPPER.readTree(tamperedTarget.resolve("manifest.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("state", "IN_PROGRESS");
        writeManifest(tamperedTarget, tree);

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(tamperedTarget).isDirectory();
    }

    @Test
    void metadataWriteRejectsPreExistingTemporarySymlinkWithoutChangingExternalFile() throws Exception {
        Path externalFile = tempDir.getParent().resolve("metadata-outside.txt");
        Files.writeString(externalFile, "external-before", StandardCharsets.UTF_8);
        Files.createDirectories(controlRoot());
        Path temporaryPath = controlRoot().resolve("snapshots.json.tmp");
        try {
            Files.createSymbolicLink(temporaryPath, externalFile);
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }

        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        assertThatThrownBy(() -> service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001")).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(externalFile).hasContent("external-before");
        assertThat(temporaryPath).isSymbolicLink();
    }

    @Test
    void metadataWriteRejectsSymlinkAncestorBeforeCreatingMissingParent() throws Exception {
        Path externalRoot = tempDir.getParent().resolve("metadata-symlink-target");
        Files.createDirectories(externalRoot);
        Path symlinkAncestor = tempDir.resolve("control-link");
        try {
            Files.createSymbolicLink(symlinkAncestor, externalRoot);
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }

        Path metadataPath = symlinkAncestor.resolve("missing-parent").resolve("snapshots.json");
        PersistenceSnapshotStore store = new PersistenceSnapshotStore(metadataPath, OBJECT_MAPPER);

        assertThatThrownBy(() -> store.writeAll(List.of()))
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(externalRoot.resolve("missing-parent")).doesNotExist();
        try (var entries = Files.list(externalRoot)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void restorePreflightConvertsMalformedManifestFieldsToStableBlockedResult() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();
        byte[] originalBytes = Files.readAllBytes(snapshotRoot().resolve(manifest.snapshotId()).resolve("manifest.json"));

        List<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>> mutations = List.of(
                root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("artifacts").get(0)).put("kind", "UNKNOWN"),
                root -> root.put("createdAt", "not-an-instant"),
                root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("artifacts").get(0)).putNull("artifactId"),
                root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("artifacts").get(0)).put("schemaVersion", "one"),
                root -> root.put("artifactCount", "one"),
                root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("artifacts").get(0)).put("sizeBytes", "many"),
                root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("artifacts").get(0)).put("recordCount", "many"));

        for (java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation : mutations) {
            com.fasterxml.jackson.databind.node.ObjectNode tree = (com.fasterxml.jackson.databind.node.ObjectNode)
                    OBJECT_MAPPER.readTree(originalBytes);
            mutation.accept(tree);
            writeManifest(snapshotRoot().resolve(manifest.snapshotId()), tree);

            assertThatCode(() -> service.restorePreflight(manifest.snapshotId()))
                    .doesNotThrowAnyException();
            assertThat(service.restorePreflight(manifest.snapshotId()))
                    .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                            PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                    .containsExactly("BLOCKED", "PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    @Test
    void restorePreflightRejectsNonBlankRelativePathThatDoesNotMatchArtifactId() throws Exception {
        writeJson("data/one.json", "{\"same\":true}");
        writeJson("data/two.json", "{\"same\":true}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("one", PersistenceArtifactKind.FILE, "data/one.json", true, true),
                definition("two", PersistenceArtifactKind.FILE, "data/two.json", true, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();
        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        byte[] originalBytes = Files.readAllBytes(snapshotDir.resolve("manifest.json"));

        for (String relativePath : List.of("artifacts/two/data", "manifest.json")) {
            com.fasterxml.jackson.databind.node.ObjectNode tree = (com.fasterxml.jackson.databind.node.ObjectNode)
                    OBJECT_MAPPER.readTree(originalBytes);
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree.withArray("artifacts").get(0))
                    .put("relativePath", relativePath);
            writeManifest(snapshotDir, tree);

            assertThat(service.restorePreflight(manifest.snapshotId()))
                    .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                            PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                    .containsExactly("BLOCKED", "PERSISTENCE_SNAPSHOT_INVALID");
        }
    }

    @Test
    void restorePreflightRejectsReadyArtifactStrippedToBlankPath() throws Exception {
        writeJson("data/optional.json", "{\"present\":true}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("optional-state", PersistenceArtifactKind.FILE, "data/optional.json", false, true)));
        PersistenceSnapshotService service = service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"));
        PersistenceSnapshotManifest manifest = service.createSnapshot();
        Path snapshotDir = snapshotRoot().resolve(manifest.snapshotId());
        JsonNode tree = OBJECT_MAPPER.readTree(snapshotDir.resolve("manifest.json").toFile());
        com.fasterxml.jackson.databind.node.ObjectNode artifactNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) tree.withArray("artifacts").get(0);
        artifactNode.put("relativePath", "");
        artifactNode.putNull("sizeBytes");
        artifactNode.putNull("sha256");
        artifactNode.putNull("recordCount");
        writeManifest(snapshotDir, tree);

        assertThat(service.restorePreflight(manifest.snapshotId()))
                .extracting(PersistenceSnapshotService.RestorePreflightResult::status,
                        PersistenceSnapshotService.RestorePreflightResult::reasonCode)
                .containsExactly("BLOCKED", "PERSISTENCE_SNAPSHOT_INVALID");
    }

    @Test
    void snapshotAndMetadataPublicationFailClosedWhenAtomicMoveIsUnsupported() throws Exception {
        writeJson("data/governance/state.json", "{\"alpha\":\"live\"}");
        PersistenceArtifactCatalog catalog = catalog(List.of(
                definition("governance-state", PersistenceArtifactKind.FILE, "data/governance/state.json", true, true)));
        PersistenceSnapshotStore.AtomicMove unsupported = (source, target, options) -> {
            throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
        };

        assertThatThrownBy(() -> service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_1,
                snapshotIds("snapshot-20260824t090000z-0001"), unsupported).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
        assertThat(snapshotRoot().resolve("snapshot-20260824t090000z-0001")).doesNotExist();

        PersistenceSnapshotStore.AtomicMove metadataUnsupported = (source, target, options) -> {
            if (target.getFileName().toString().equals("snapshots.json")) {
                throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
            }
            Files.move(source, target, options);
        };
        assertThatThrownBy(() -> service(catalog, new PersistenceIntegrityService(tempDir), CLOCK_2,
                snapshotIds("snapshot-20260824t100000z-0002"), metadataUnsupported).createSnapshot())
                .isInstanceOf(PersistenceControlException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_SNAPSHOT_INVALID");
    }

    private PersistenceSnapshotArtifact artifact(PersistenceSnapshotManifest manifest, String artifactId) {
        return manifest.artifacts().stream()
                .filter(candidate -> candidate.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow();
    }

    private PersistenceArtifactCatalog.Definition definition(String artifactId,
                                                             PersistenceArtifactKind kind,
                                                             String storage,
                                                             boolean critical,
                                                             boolean includeInSnapshot) {
        return new PersistenceArtifactCatalog.Definition(artifactId, kind, 1, storage, critical, includeInSnapshot);
    }

    private PersistenceArtifactCatalog catalog(List<PersistenceArtifactCatalog.Definition> definitions) {
        return new PersistenceArtifactCatalog(properties(10), tempDir, definitions);
    }

    private PersistenceSnapshotService service(PersistenceArtifactCatalog catalog,
                                               PersistenceIntegrityService integrityService,
                                               Clock clock,
                                               Supplier<String> snapshotIds) {
        return service(catalog, integrityService, properties(10), clock, snapshotIds);
    }

    private PersistenceArtifactCatalog catalog(List<PersistenceArtifactCatalog.Definition> definitions,
                                               PersistenceControlProperties properties) {
        return new PersistenceArtifactCatalog(properties, tempDir, definitions);
    }

    private PersistenceSnapshotService service(PersistenceArtifactCatalog catalog,
                                               PersistenceIntegrityService integrityService,
                                               PersistenceControlProperties properties,
                                               Clock clock,
                                               Supplier<String> snapshotIds) {
        return new PersistenceSnapshotService(
                catalog,
                properties,
                tempDir,
                integrityService,
                clock,
                snapshotIds);
    }

    private PersistenceSnapshotService service(PersistenceArtifactCatalog catalog,
                                               PersistenceIntegrityService integrityService,
                                               Clock clock,
                                               Supplier<String> snapshotIds,
                                               PersistenceSnapshotStore.AtomicMove atomicMove) {
        return new PersistenceSnapshotService(
                catalog,
                properties(10),
                tempDir,
                integrityService,
                clock,
                snapshotIds,
                atomicMove);
    }

    private PersistenceSnapshotService service(PersistenceArtifactCatalog catalog,
                                               PersistenceIntegrityService integrityService,
                                               PersistenceControlProperties properties,
                                               Clock clock,
                                               Supplier<String> snapshotIds,
                                               PersistenceSnapshotService.BeforeSnapshotDeletion beforeSnapshotDeletion) {
        return new PersistenceSnapshotService(
                catalog,
                properties,
                tempDir,
                integrityService,
                clock,
                snapshotIds,
                (source, target, options) -> Files.move(source, target, options),
                beforeSnapshotDeletion);
    }

    private PersistenceControlProperties properties(int retention) {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setBackend("json");
        properties.setControlStorage("control");
        properties.setSnapshotStorage("snapshots");
        properties.setStartupMode("fail-closed");
        properties.setManifestRetention(retention);
        return properties;
    }

    private PersistenceIntegrityService overrideIntegrity(Map<Path, PersistenceArtifactStatus> overrides) {
        return new OverrideIntegrityService(tempDir, overrides);
    }

    private PersistenceArtifactStatus readyStatus(PersistenceArtifactDescriptor descriptor,
                                                  Long sizeBytes,
                                                  String sha256,
                                                  Long recordCount) {
        return status(descriptor, PersistenceArtifactState.READY, sizeBytes, sha256, recordCount, "");
    }

    private PersistenceArtifactStatus status(PersistenceArtifactDescriptor descriptor,
                                             PersistenceArtifactState state,
                                             Long sizeBytes,
                                             String sha256,
                                             Long recordCount,
                                             String stableReasonCode) {
        return new PersistenceArtifactStatus(
                descriptor.artifactId(),
                state,
                descriptor.schemaVersion(),
                descriptor.schemaVersion(),
                sizeBytes,
                sha256,
                recordCount,
                Instant.parse("2026-08-24T08:30:00Z"),
                stableReasonCode);
    }

    private Supplier<String> snapshotIds(String... snapshotIds) {
        AtomicInteger index = new AtomicInteger();
        return () -> snapshotIds[index.getAndIncrement()];
    }

    private Path writeJson(String relativePath, String payload) throws IOException {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, payload, StandardCharsets.UTF_8);
        return path;
    }

    private void writeDirectory(Map<String, String> entries) throws IOException {
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            Path path = tempDir.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private Path snapshotRoot() {
        return tempDir.resolve("snapshots");
    }

    private void writeManifest(Path snapshotDir, JsonNode tree) throws IOException {
        byte[] manifestBytes = OBJECT_MAPPER.writeValueAsBytes(tree);
        Files.write(snapshotDir.resolve("manifest.json"), manifestBytes);
        Files.writeString(snapshotDir.resolve("manifest.sha256"), sha256(manifestBytes), StandardCharsets.UTF_8);
    }

    private void deleteTree(Path root) throws IOException {
        if (Files.notExists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private Path controlRoot() {
        return tempDir.resolve("control");
    }

    private Set<String> fieldNames(JsonNode entry) {
        return entry.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String repeat(String hexDigit) {
        return hexDigit.repeat(64);
    }

    private static final class OverrideIntegrityService extends PersistenceIntegrityService {
        private final PersistenceIntegrityService delegate;
        private final Map<Path, PersistenceArtifactStatus> overrides;

        private OverrideIntegrityService(Path configuredRoot, Map<Path, PersistenceArtifactStatus> overrides) {
            super(configuredRoot);
            this.delegate = new PersistenceIntegrityService(configuredRoot);
            this.overrides = overrides.entrySet().stream()
                    .collect(Collectors.toMap(entry -> entry.getKey().toAbsolutePath().normalize(), Map.Entry::getValue));
        }

        @Override
        public PersistenceArtifactStatus inspect(PersistenceArtifactDescriptor descriptor) {
            Path normalized = descriptor.storagePath().toAbsolutePath().normalize();
            PersistenceArtifactStatus override = overrides.get(normalized);
            if (override != null) {
                return override;
            }
            return delegate.inspect(descriptor);
        }
    }
}
