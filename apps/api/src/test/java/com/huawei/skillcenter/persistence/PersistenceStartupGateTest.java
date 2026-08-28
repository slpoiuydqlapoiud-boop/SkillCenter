package com.huawei.skillcenter.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PersistenceStartupGateTest {
    @TempDir
    Path tempDir;

    @Test
    void optionalMissingAssetProducesDegradedWithoutCreatingControlFiles() {
        PersistenceControlProperties properties = properties();
        PersistenceControlService service = service(properties, List.of(
                definition("optional", false, 1, "data/optional.json")));

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("DEGRADED");
        assertThat(status.artifacts()).singleElement().extracting(
                PersistenceControlService.PersistenceArtifactStatusView::state)
                .isEqualTo("OPTIONAL_MISSING");
        assertThat(Files.exists(tempDir.resolve("data/control/migration-journal.json"))).isFalse();
    }

    @Test
    void criticalMissingAssetFailsClosedAtStartupWithoutWritingBusinessData() {
        PersistenceControlProperties properties = properties();
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "data/critical.json")), true);

        assertThat(service.status().overall()).isEqualTo("FAIL_CLOSED");
        assertThatThrownBy(service::validateStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PERSISTENCE_ARTIFACT_MISSING");
        assertThat(Files.exists(tempDir.resolve("data/critical.json"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("data/control/migration-journal.json"))).isFalse();
    }

    @Test
    void corruptedCriticalAssetFailsClosedWithStableStatus() throws Exception {
        PersistenceControlProperties properties = properties();
        Path artifact = tempDir.resolve("data/critical.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{malformed");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "data/critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.artifacts()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo("CORRUPTED");
            assertThat(value.stableReasonCode()).isEqualTo("PERSISTENCE_ARTIFACT_CORRUPTED");
        });
    }

    @Test
    void corruptedOptionalAssetFailsClosedInsteadOfBeingReportedReady() throws Exception {
        PersistenceControlProperties properties = properties();
        Path artifact = tempDir.resolve("data/optional.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{malformed");
        PersistenceControlService service = service(properties, List.of(
                definition("optional", false, 1, "data/optional.json")));

        assertThat(service.status().overall()).isEqualTo("FAIL_CLOSED");
    }

    @Test
    void malformedMigrationJournalFailsClosedWithoutRepairWrite() throws Exception {
        PersistenceControlProperties properties = properties();
        Path artifact = tempDir.resolve("data/critical.json");
        Path journal = tempDir.resolve("data/control/migration-journal.json");
        Files.createDirectories(artifact.getParent());
        Files.createDirectories(journal.getParent());
        Files.writeString(artifact, "{}");
        Files.writeString(journal, "not-json");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "data/critical.json")));

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.controlPlaneReasonCodes())
                .contains("PERSISTENCE_ARTIFACT_CORRUPTED");
        assertThat(Files.readString(journal)).isEqualTo("not-json");
    }

    @Test
    void unsupportedMigrationStateFailsClosed() throws Exception {
        PersistenceControlProperties properties = properties();
        Path artifact = tempDir.resolve("data/critical.json");
        Path journal = tempDir.resolve("data/control/migration-journal.json");
        Files.createDirectories(artifact.getParent());
        Files.createDirectories(journal.getParent());
        Files.writeString(artifact, "{}");
        Files.writeString(journal, "[{\"artifactId\":\"critical\",\"fromVersion\":2,\"toVersion\":3,\"executedAt\":\"2026-08-25T00:00:00Z\",\"result\":\"SUCCESS\",\"requestId\":\"test\"}]");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 2, "data/critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.artifacts()).singleElement().extracting(
                PersistenceControlService.PersistenceArtifactStatusView::state)
                .isEqualTo("MIGRATION_UNSUPPORTED");
    }

    @Test
    void missingMigrationPathIsReportedAsRequiredAndBlocksStartup() throws Exception {
        PersistenceControlProperties properties = properties();
        Path artifact = tempDir.resolve("data/critical.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{}");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 2, "data/critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.artifacts()).singleElement().extracting(
                PersistenceControlService.PersistenceArtifactStatusView::state)
                .isEqualTo("MIGRATION_REQUIRED");
        assertThatThrownBy(service::validateStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PERSISTENCE_MIGRATION_REQUIRED");
    }

    @Test
    void cleanStartupIsReadyAndDoesNotWriteMigrationJournal() throws Exception {
        PersistenceControlProperties properties = properties();
        Path critical = tempDir.resolve("data/critical.json");
        Path optional = tempDir.resolve("data/optional.json");
        Files.createDirectories(critical.getParent());
        Files.writeString(critical, "{}");
        Files.writeString(optional, "[]");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "data/critical.json"),
                definition("optional", false, 1, "data/optional.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();
        service.validateStartup();

        assertThat(status.overall()).isEqualTo("READY");
        assertThat(service.startupStatus().overall()).isEqualTo(status.overall());
        assertThat(service.startupStatus().artifacts())
                .extracting(PersistenceControlService.PersistenceArtifactStatusView::state)
                .containsExactly("READY", "READY");
        assertThat(Files.exists(tempDir.resolve("data/control"))).isFalse();
    }

    @Test
    void invalidBackendAndStoragePathAreRejectedBeforeStartup() {
        PersistenceControlProperties invalidBackend = properties();
        invalidBackend.setBackend("oracle");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersistenceArtifactCatalog(
                        invalidBackend, tempDir, List.of(definition("critical", true, 1, "data/critical.json"))));

        PersistenceControlProperties invalidPath = properties();
        invalidPath.setSnapshotStorage("../outside");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> invalidPath.validate(tempDir));
    }

    @Test
    void postgresBackendFailureBlocksSharedStartupStatus() {
        PersistenceControlProperties properties = postgresProperties();
        PersistenceControlService service = service(
                properties,
                List.of(definition("quality-evidence", false, 1, "data/quality-evidence.json")),
                false,
                backend(PersistenceBackendStatus.failClosed(
                        "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", null, null)));

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.controlPlaneReasonCodes()).containsExactly("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThat(status.controlPlaneReasonCodes()).noneMatch(reason -> reason.contains("password"));
    }

    @Test
    void postgresQualityEvidenceDoesNotReportMissingJsonFileAsThePrimaryArtifactFailure() {
        PersistenceControlProperties properties = postgresProperties();
        PersistenceArtifactCatalog catalog = new PersistenceArtifactCatalog(properties, tempDir, List.of(
                definition("quality-evidence", false, 1, "data/quality-evidence.json")));
        PersistenceControlService service = new PersistenceControlService(
                catalog,
                new PersistenceIntegrityService(tempDir),
                mock(PersistenceSnapshotService.class),
                properties,
                tempDir,
                false,
                backend(PersistenceBackendStatus.ready("postgresql", "1", 7L)));

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(catalog.find("quality-evidence")).hasValueSatisfying(descriptor ->
                assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
        assertThat(status.overall()).isEqualTo("READY");
        assertThat(status.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.artifactId()).isEqualTo("quality-evidence");
            assertThat(artifact.state()).isEqualTo("READY");
            assertThat(artifact.stableReasonCode()).isBlank();
        });
    }

    @Test
    void snapshotStorageRootSymlinkFailsClosedWithoutCreatingThroughTheLink() throws Exception {
        Path externalRoot = tempDir.resolveSibling(tempDir.getFileName() + "-snapshot-root-target");
        Files.createDirectories(externalRoot);
        Path snapshotRoot = tempDir.resolve("snapshots");
        createSymlinkOrSkip(snapshotRoot, externalRoot);
        Files.writeString(tempDir.resolve("critical.json"), "{}");

        PersistenceControlProperties properties = properties();
        properties.setControlStorage("control");
        properties.setSnapshotStorage("snapshots");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.controlPlaneReasonCodes())
                .containsExactly("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThatThrownBy(service::validateStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThat(Files.exists(externalRoot.resolve("created-by-startup"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void existingSnapshotStorageAncestorSymlinkFailsClosedWithoutCreatingThroughTheLink() throws Exception {
        Path externalRoot = tempDir.resolveSibling(tempDir.getFileName() + "-snapshot-ancestor-target");
        Files.createDirectories(externalRoot);
        Path linkedAncestor = tempDir.resolve("data");
        createSymlinkOrSkip(linkedAncestor, externalRoot);
        Files.writeString(tempDir.resolve("critical.json"), "{}");

        PersistenceControlProperties properties = properties();
        properties.setControlStorage("control");
        properties.setSnapshotStorage("data/backups");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.controlPlaneReasonCodes())
                .containsExactly("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThatThrownBy(service::validateStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThat(Files.exists(externalRoot.resolve("backups"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void existingSnapshotStorageRootThatIsNotDirectoryFailsClosed() throws Exception {
        Files.writeString(tempDir.resolve("snapshots"), "not a directory");
        Files.writeString(tempDir.resolve("critical.json"), "{}");

        PersistenceControlProperties properties = properties();
        properties.setControlStorage("control");
        properties.setSnapshotStorage("snapshots");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.controlPlaneReasonCodes())
                .containsExactly("PERSISTENCE_CONTROL_PLANE_ERROR");
    }

    @Test
    void existingControlStorageRootThatIsNotDirectoryFailsClosedWithoutCreatingJournal() throws Exception {
        Files.writeString(tempDir.resolve("control"), "not a directory");
        Files.writeString(tempDir.resolve("critical.json"), "{}");

        PersistenceControlProperties properties = properties();
        properties.setControlStorage("control");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.controlPlaneReasonCodes())
                .containsExactly("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThat(Files.exists(tempDir.resolve("control/migration-journal.json"), LinkOption.NOFOLLOW_LINKS))
                .isFalse();
    }

    @Test
    void existingControlStorageAncestorThatIsNotDirectoryFailsClosedWithoutCreatingJournal() throws Exception {
        Files.writeString(tempDir.resolve("data"), "not a directory");
        Files.writeString(tempDir.resolve("critical.json"), "{}");

        PersistenceControlProperties properties = properties();
        properties.setControlStorage("data/control");
        PersistenceControlService service = service(properties, List.of(
                definition("critical", true, 1, "critical.json")), true);

        PersistenceControlService.PersistenceStatusView status = service.status();

        assertThat(status.overall()).isEqualTo("FAIL_CLOSED");
        assertThat(status.controlPlaneReasonCodes())
                .containsExactly("PERSISTENCE_CONTROL_PLANE_ERROR");
        assertThat(Files.exists(tempDir.resolve("data/control/migration-journal.json"), LinkOption.NOFOLLOW_LINKS))
                .isFalse();
    }

    private PersistenceControlService service(PersistenceControlProperties properties,
                                              List<PersistenceArtifactCatalog.Definition> definitions) {
        return service(properties, definitions, false);
    }

    private PersistenceControlService service(PersistenceControlProperties properties,
                                              List<PersistenceArtifactCatalog.Definition> definitions,
                                              boolean startupGateEnabled) {
        return service(properties, definitions, startupGateEnabled, new JsonPersistenceBackend());
    }

    private PersistenceControlService service(PersistenceControlProperties properties,
                                              List<PersistenceArtifactCatalog.Definition> definitions,
                                              boolean startupGateEnabled,
                                              PersistenceBackend backend) {
        PersistenceArtifactCatalog catalog = new PersistenceArtifactCatalog(properties, tempDir, definitions);
        return new PersistenceControlService(
                catalog,
                new PersistenceIntegrityService(tempDir),
                mock(PersistenceSnapshotService.class),
                properties,
                tempDir,
                startupGateEnabled,
                backend);
    }

    private PersistenceControlProperties properties() {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setControlStorage("data/control");
        properties.setSnapshotStorage("data/backups");
        properties.setStartupMode("fail-closed");
        return properties;
    }

    private PersistenceControlProperties postgresProperties() {
        PersistenceControlProperties properties = properties();
        properties.setBackend(" PostgreSQL ");
        properties.setQualityEvidenceBackend(" PostgreSQL ");
        return properties;
    }

    private PersistenceBackend backend(PersistenceBackendStatus status) {
        return new PersistenceBackend() {
            @Override
            public String backendId() {
                return status.backendId();
            }

            @Override
            public PersistenceBackendStatus status() {
                return status;
            }
        };
    }

    private PersistenceArtifactCatalog.Definition definition(String id,
                                                             boolean critical,
                                                             int schemaVersion,
                                                             String storage) {
        return new PersistenceArtifactCatalog.Definition(
                id, PersistenceArtifactKind.FILE, schemaVersion, storage, critical, true);
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
}
