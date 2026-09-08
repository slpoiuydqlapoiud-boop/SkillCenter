package com.huawei.skillcenter.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceArtifactCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void artifactsAreSortedByStableArtifactIdAndResolveNormalizedPaths() {
        PersistenceArtifactCatalog catalog = catalog(tempDir);

        assertThat(catalog.artifacts()).extracting(PersistenceArtifactDescriptor::artifactId)
                .containsExactly(
                        "benchmarks",
                        "execution-environments",
                        "execution-records",
                        "governance-state",
                        "operations-metrics",
                        "optimization-assessments",
                        "optimization-experiments",
                        "optimization-observations",
                        "optimization-work-items",
                        "packages",
                        "production-evidence",
                        "quality-evidence",
                        "releases",
                        "runtime-summaries",
                        "skill-relations",
                        "skill-scopes");

        assertThat(catalog.find("governance-state")).hasValueSatisfying(descriptor ->
                assertThat(descriptor.storagePath())
                        .isEqualTo(tempDir.resolve("data/governance/state.json").toAbsolutePath().normalize()));
        assertThat(catalog.find("packages")).hasValueSatisfying(descriptor -> {
            assertThat(descriptor.kind()).isEqualTo(PersistenceArtifactKind.DIRECTORY);
            assertThat(descriptor.storagePath())
                    .isEqualTo(tempDir.resolve("data/packages").toAbsolutePath().normalize());
        });
        assertThat(catalog.find("execution-records")).hasValueSatisfying(descriptor -> {
            assertThat(descriptor.kind()).isEqualTo(PersistenceArtifactKind.FILE);
            assertThat(descriptor.storagePath())
                        .isEqualTo(tempDir.resolve("data/governance/skill-executions.json").toAbsolutePath().normalize());
        });
        assertThat(catalog.find("production-evidence")).hasValueSatisfying(descriptor ->
                assertThat(descriptor.storagePath())
                        .isEqualTo(tempDir.resolve("data/governance/production-evidence.json").toAbsolutePath().normalize()));
        assertThat(properties("json", "control/./plane", "snapshots/../snapshots", 10)
                .controlStoragePath(tempDir))
                .isEqualTo(tempDir.resolve("control/plane").toAbsolutePath().normalize());
        assertThat(properties("json", "control/./plane", "snapshots/../snapshots", 10)
                .snapshotStoragePath(tempDir))
                .isEqualTo(tempDir.resolve("snapshots").toAbsolutePath().normalize());
    }

    @Test
    void duplicateArtifactIdsAreRejected() {
        List<PersistenceArtifactCatalog.Definition> definitions = List.of(
                new PersistenceArtifactCatalog.Definition("duplicate", PersistenceArtifactKind.FILE, 1,
                        "state.json", true, true),
                new PersistenceArtifactCatalog.Definition("duplicate", PersistenceArtifactKind.FILE, 1,
                        "other.json", false, true));

        assertThatThrownBy(() -> new PersistenceArtifactCatalog(
                properties("json", "control", "snapshots", 10),
                tempDir,
                definitions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate artifactId");
    }

    @Test
    void schemaVersionsBelowOneAreRejected() {
        List<PersistenceArtifactCatalog.Definition> definitions = List.of(
                new PersistenceArtifactCatalog.Definition("governance-state", PersistenceArtifactKind.FILE, 0,
                        "state.json", true, true));

        assertThatThrownBy(() -> new PersistenceArtifactCatalog(
                properties("json", "control", "snapshots", 10),
                tempDir,
                definitions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void unsupportedBackendIsRejected() {
        assertThatThrownBy(() -> catalog(properties("memory", "control", "snapshots", 10), tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backend");
    }

    @Test
    void controlAndSnapshotRootsMustRemainWithinConfiguredParent() {
        Path outside = tempDir.getParent().resolve(tempDir.getFileName() + "-outside").toAbsolutePath().normalize();

        assertThatThrownBy(() -> properties("json", "../control", "snapshots", 10).controlStoragePath(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("controlStorage");
        assertThatThrownBy(() -> properties("json", "control", "../snapshots", 10).snapshotStoragePath(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshotStorage");
        assertThatThrownBy(() -> properties("json", outside.toString(), "snapshots", 10).controlStoragePath(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("controlStorage");
        assertThatThrownBy(() -> properties("json", "control", outside.toString(), 10).snapshotStoragePath(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshotStorage");
    }

    @Test
    void optionalMissingArtifactsDoNotPreventCatalogConstruction() {
        PersistenceArtifactCatalog catalog = catalog(tempDir);

        assertThat(catalog.find("packages")).isPresent();
        assertThat(catalog.find("benchmarks")).isPresent();
        assertThat(catalog.find("execution-records")).isPresent();
        assertThat(catalog.artifacts()).hasSize(16);
    }

    @Test
    void releaseArtifactUsesPostgresqlBackendOnlyWhenExplicitlySelected() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setReleaseBackend("postgresql");

        assertThat(new PersistenceArtifactCatalog(properties, tempDir).find("releases"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
    }

    @Test
    void productionEvidenceArtifactUsesPostgresqlBackendOnlyWhenExplicitlySelected() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setProductionEvidenceBackend("postgresql");

        assertThat(new PersistenceArtifactCatalog(properties, tempDir).find("production-evidence"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
    }

    @Test
    void governanceStateArtifactUsesPostgresqlBackendOnlyWhenExplicitlySelected() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setGovernanceBackend("postgresql");

        assertThat(new PersistenceArtifactCatalog(properties, tempDir).find("governance-state"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
    }

    @Test
    void executionEnvironmentArtifactUsesPostgresqlBackendOnlyWhenExplicitlySelected() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setExecutionEnvironmentBackend("postgresql");

        assertThat(new PersistenceArtifactCatalog(properties, tempDir).find("execution-environments"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
    }

    @Test
    void benchmarkArtifactUsesPostgresqlBackendOnlyWhenExplicitlySelected() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setBenchmarkBackend("postgresql");

        assertThat(new PersistenceArtifactCatalog(properties, tempDir).find("benchmarks"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
    }

    @Test
    void skillScopeAndRelationArtifactsUsePostgresqlOnlyWhenExplicitlySelected() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setSkillScopeBackend("postgresql");
        properties.setSkillRelationBackend("postgresql");

        PersistenceArtifactCatalog catalog = new PersistenceArtifactCatalog(properties, tempDir);

        assertThat(catalog.find("skill-scopes"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
        assertThat(catalog.find("skill-relations"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
    }

    @Test
    void optimizationObservationAndAssessmentArtifactsFollowPostgresqlExperimentBackend() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setOptimizationExperimentBackend("postgresql");

        PersistenceArtifactCatalog catalog = new PersistenceArtifactCatalog(properties, tempDir);

        assertThat(catalog.find("optimization-observations"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
        assertThat(catalog.find("optimization-assessments"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.physicalBackend()).isEqualTo("postgresql"));
    }

    @Test
    void sharedRuntimeAndMetricsStoresAreNotRegisteredAsJsonSnapshotArtifacts() {
        PersistenceControlProperties properties = properties("postgresql", "control", "snapshots", 10);
        properties.setRuntimeSummaryBackend("redis");
        properties.setOperationsMetricsStorage("redis");

        PersistenceArtifactCatalog catalog = new PersistenceArtifactCatalog(properties, tempDir);

        assertThat(catalog.find("runtime-summaries")).isEmpty();
        assertThat(catalog.find("operations-metrics")).isEmpty();
    }

    @Test
    void persistedInvocationEventsRemainMappedToGovernanceState() {
        PersistenceArtifactCatalog catalog = catalog(tempDir);

        assertThat(catalog.find("governance-state")).isPresent();
        assertThat(catalog.find("invocation-events")).isEmpty();
    }

    @Test
    void springConstructorShapeWiresExecutionStorageIntoStableArtifacts() {
        PersistenceArtifactCatalog catalog = new PersistenceArtifactCatalog(
                properties("json", "control", "snapshots", 10),
                tempDir.resolve("cfg/governance.json").toString(),
                tempDir.resolve("cfg/production-evidence.json").toString(),
                tempDir.resolve("cfg/scopes.json").toString(),
                tempDir.resolve("cfg/relations.json").toString(),
                tempDir.resolve("cfg/quality.json").toString(),
                tempDir.resolve("cfg/benchmarks.json").toString(),
                tempDir.resolve("cfg/execution-environments.json").toString(),
                tempDir.resolve("cfg/skill-executions.json").toString(),
                tempDir.resolve("cfg/releases.json").toString(),
                tempDir.resolve("cfg/runtime.json").toString(),
                tempDir.resolve("cfg/work-items.json").toString(),
                tempDir.resolve("cfg/experiments.json").toString(),
                tempDir.resolve("cfg/observations.json").toString(),
                tempDir.resolve("cfg/assessments.json").toString(),
                tempDir.resolve("cfg/metrics.json").toString(),
                tempDir.resolve("cfg/packages").toString());

        assertThat(catalog.find("execution-records")).hasValueSatisfying(descriptor ->
                assertThat(descriptor.storagePath())
                        .isEqualTo(tempDir.resolve("cfg/skill-executions.json").toAbsolutePath().normalize()));
    }

    private PersistenceArtifactCatalog catalog(Path baseDir) {
        return catalog(properties("json", "control", "snapshots", 10), baseDir);
    }

    private PersistenceArtifactCatalog catalog(PersistenceControlProperties properties, Path baseDir) {
        return new PersistenceArtifactCatalog(properties, baseDir);
    }

    private PersistenceControlProperties properties(String backend, String controlStorage,
                                                    String snapshotStorage, int manifestRetention) {
        PersistenceControlProperties properties = new PersistenceControlProperties();
        properties.setBackend(backend);
        properties.setControlStorage(controlStorage);
        properties.setSnapshotStorage(snapshotStorage);
        properties.setStartupMode("fail-closed");
        properties.setManifestRetention(manifestRetention);
        return properties;
    }
}
