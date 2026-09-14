package com.huawei.skillcenter.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class PersistenceArtifactCatalog {
    private final List<PersistenceArtifactDescriptor> artifacts;

    public PersistenceArtifactCatalog(
            PersistenceControlProperties properties,
            String governanceStorage,
            String productionEvidenceStorage,
            String skillScopeStorage,
            String skillRelationsStorage,
            String qualityEvidenceStorage,
            String benchmarkStorage,
            String executionEnvironmentStorage,
            String executionStorage,
            String releaseStorage,
            String runtimeSummaryStorage,
            String optimizationWorkItemStorage,
            String optimizationExperimentStorage,
            String optimizationObservationStorage,
            String optimizationAssessmentStorage,
            String operationsMetricsStorage,
            String packageStorage) {
        this(properties, configuredRootPath(System.getProperty("user.dir")), definitions(
                governanceStorage,
                productionEvidenceStorage,
                skillScopeStorage,
                skillRelationsStorage,
                qualityEvidenceStorage,
                benchmarkStorage,
                executionEnvironmentStorage,
                executionStorage,
                releaseStorage,
                runtimeSummaryStorage,
                optimizationWorkItemStorage,
                optimizationExperimentStorage,
                optimizationObservationStorage,
                optimizationAssessmentStorage,
                operationsMetricsStorage,
                packageStorage));
    }

    @Autowired
    public PersistenceArtifactCatalog(
            PersistenceControlProperties properties,
            @Value("${skill-center.persistence.configured-root:${user.dir}}") String configuredRoot,
            @Value("${skill-center.governance-storage:./data/governance/state.json}") String governanceStorage,
            @Value("${skill-center.production-evidence-storage:./data/governance/production-evidence.json}") String productionEvidenceStorage,
            @Value("${skill-center.skill-scope-storage:./data/governance/skill-scopes.json}") String skillScopeStorage,
            @Value("${skill-center.skill-relations-storage:./data/governance/skill-relations.json}") String skillRelationsStorage,
            @Value("${skill-center.quality-evidence-storage:./data/governance/quality-evidence.json}") String qualityEvidenceStorage,
            @Value("${skill-center.benchmark-storage:./data/governance/benchmarks.json}") String benchmarkStorage,
            @Value("${skill-center.execution-environment-storage:./data/governance/execution-environments.json}") String executionEnvironmentStorage,
            @Value("${skill-center.execution-storage:./data/governance/skill-executions.json}") String executionStorage,
            @Value("${skill-center.release-storage:./data/governance/releases.json}") String releaseStorage,
            @Value("${skill-center.runtime-summary-storage:./data/governance/runtime-summaries.json}") String runtimeSummaryStorage,
            @Value("${skill-center.optimization-work-item-storage:./data/governance/optimization-work-items.json}") String optimizationWorkItemStorage,
            @Value("${skill-center.optimization-experiment-storage:./data/governance/optimization-experiments.json}") String optimizationExperimentStorage,
            @Value("${skill-center.optimization-experiment-observation-storage:./data/governance/optimization-experiment-observations.json}") String optimizationObservationStorage,
            @Value("${skill-center.optimization-experiment-assessment-storage:./data/governance/optimization-experiment-assessments.json}") String optimizationAssessmentStorage,
            @Value("${skill-center.operations.metrics-storage:./data/operations/metrics.json}") String operationsMetricsStorage,
            @Value("${skill-center.package-storage:./data/packages}") String packageStorage) {
        this(properties, configuredRootPath(configuredRoot), definitions(
                governanceStorage,
                productionEvidenceStorage,
                skillScopeStorage,
                skillRelationsStorage,
                qualityEvidenceStorage,
                benchmarkStorage,
                executionEnvironmentStorage,
                executionStorage,
                releaseStorage,
                runtimeSummaryStorage,
                optimizationWorkItemStorage,
                optimizationExperimentStorage,
                optimizationObservationStorage,
                optimizationAssessmentStorage,
                operationsMetricsStorage,
                packageStorage));
    }

    PersistenceArtifactCatalog(PersistenceControlProperties properties, Path configuredParent) {
        this(properties, configuredParent, definitions(
                "./data/governance/state.json",
                "./data/governance/production-evidence.json",
                "./data/governance/skill-scopes.json",
                "./data/governance/skill-relations.json",
                "./data/governance/quality-evidence.json",
                "./data/governance/benchmarks.json",
                "./data/governance/execution-environments.json",
                "./data/governance/skill-executions.json",
                "./data/governance/releases.json",
                "./data/governance/runtime-summaries.json",
                "./data/governance/optimization-work-items.json",
                "./data/governance/optimization-experiments.json",
                "./data/governance/optimization-experiment-observations.json",
                "./data/governance/optimization-experiment-assessments.json",
                "./data/operations/metrics.json",
                "./data/packages"));
    }

    PersistenceArtifactCatalog(PersistenceControlProperties properties, Path configuredParent, List<Definition> definitions) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        properties.validate(configuredParent);
        Path baseDir = configuredParent == null
                ? Path.of("").toAbsolutePath().normalize()
                : configuredParent.toAbsolutePath().normalize();
        this.artifacts = buildArtifacts(baseDir, definitions, properties);
    }

    public List<PersistenceArtifactDescriptor> artifacts() {
        return artifacts;
    }

    public Optional<PersistenceArtifactDescriptor> find(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return Optional.empty();
        }
        String normalizedId = artifactId.trim();
        return artifacts.stream()
                .filter(descriptor -> descriptor.artifactId().equals(normalizedId))
                .findFirst();
    }

    private List<PersistenceArtifactDescriptor> buildArtifacts(Path baseDir,
                                                                List<Definition> definitions,
                                                                PersistenceControlProperties properties) {
        if (definitions == null) {
            throw new IllegalArgumentException("definitions must not be null");
        }
        Set<String> artifactIds = new HashSet<>();
        List<PersistenceArtifactDescriptor> resolved = new ArrayList<>();
        for (Definition definition : definitions) {
            if (definition == null) {
                throw new IllegalArgumentException("definition must not be null");
            }
            String artifactId = definition.artifactId().trim();
            if (!isControlPlaneManaged(artifactId, properties)) {
                continue;
            }
            if (!artifactIds.add(artifactId)) {
                throw new IllegalArgumentException("duplicate artifactId: " + artifactId);
            }
            resolved.add(new PersistenceArtifactDescriptor(
                    artifactId,
                    definition.kind(),
                    definition.schemaVersion(),
                    resolvePath(baseDir, definition.storage()),
                    definition.critical(),
                    definition.includeInSnapshot(),
                    physicalBackend(artifactId, properties)));
        }
        resolved.sort(Comparator.comparing(PersistenceArtifactDescriptor::artifactId));
        return List.copyOf(resolved);
    }

    private boolean isControlPlaneManaged(String artifactId, PersistenceControlProperties properties) {
        if ("runtime-summaries".equals(artifactId)
                && "redis".equals(properties.normalizedRuntimeSummaryBackend())) {
            return false;
        }
        if ("operations-metrics".equals(artifactId)) {
            String storage = properties.normalizedOperationsMetricsStorage();
            return !storage.isBlank() && !"redis".equals(storage) && !"memory".equals(storage);
        }
        return true;
    }

    private Path resolvePath(Path baseDir, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("storage path must not be blank");
        }
        Path candidate = Path.of(configuredPath.trim());
        return candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).toAbsolutePath().normalize();
    }

    private String physicalBackend(String artifactId, PersistenceControlProperties properties) {
        if ("governance-state".equals(artifactId)
                && ("mysql".equals(properties.normalizedGovernanceBackend())
                || "postgresql".equals(properties.normalizedGovernanceBackend()))) {
            return properties.normalizedGovernanceBackend();
        }
        if ("production-evidence".equals(artifactId)
                && "postgresql".equals(properties.normalizedProductionEvidenceBackend())) {
            return "postgresql";
        }
        if ("quality-evidence".equals(artifactId)
                && "postgresql".equals(properties.normalizedQualityEvidenceBackend())) {
            return "postgresql";
        }
        if ("benchmarks".equals(artifactId)
                && "postgresql".equals(properties.normalizedBenchmarkBackend())) {
            return "postgresql";
        }
        if ("skill-scopes".equals(artifactId)
                && "postgresql".equals(properties.normalizedSkillScopeBackend())) {
            return "postgresql";
        }
        if ("skill-relations".equals(artifactId)
                && "postgresql".equals(properties.normalizedSkillRelationBackend())) {
            return "postgresql";
        }
        if ("execution-environments".equals(artifactId)
                && "postgresql".equals(properties.normalizedExecutionEnvironmentBackend())) {
            return "postgresql";
        }
        if ("releases".equals(artifactId)
                && "postgresql".equals(properties.normalizedReleaseBackend())) {
            return "postgresql";
        }
        if ("optimization-work-items".equals(artifactId)
                && "postgresql".equals(properties.normalizedOptimizationWorkItemBackend())) {
            return "postgresql";
        }
        if (("optimization-experiments".equals(artifactId)
                || "optimization-observations".equals(artifactId)
                || "optimization-assessments".equals(artifactId))
                && "postgresql".equals(properties.normalizedOptimizationExperimentBackend())) {
            return "postgresql";
        }
        return "json";
    }

    private static List<Definition> definitions(
            String governanceStorage,
            String productionEvidenceStorage,
            String skillScopeStorage,
            String skillRelationsStorage,
            String qualityEvidenceStorage,
            String benchmarkStorage,
            String executionEnvironmentStorage,
            String executionStorage,
            String releaseStorage,
            String runtimeSummaryStorage,
            String optimizationWorkItemStorage,
            String optimizationExperimentStorage,
            String optimizationObservationStorage,
            String optimizationAssessmentStorage,
            String operationsMetricsStorage,
            String packageStorage) {
        return List.of(
                new Definition("governance-state", PersistenceArtifactKind.FILE, 1, governanceStorage, true, true),
                new Definition("production-evidence", PersistenceArtifactKind.FILE, 1, productionEvidenceStorage, false, true),
                new Definition("skill-scopes", PersistenceArtifactKind.FILE, 1, skillScopeStorage, false, true),
                new Definition("skill-relations", PersistenceArtifactKind.FILE, 1, skillRelationsStorage, false, true),
                new Definition("quality-evidence", PersistenceArtifactKind.FILE, 1, qualityEvidenceStorage, false, true),
                new Definition("benchmarks", PersistenceArtifactKind.FILE, 1, benchmarkStorage, false, true),
                new Definition("execution-environments", PersistenceArtifactKind.FILE, 1, executionEnvironmentStorage, false, true),
                new Definition("execution-records", PersistenceArtifactKind.FILE, 1, executionStorage, false, true),
                new Definition("releases", PersistenceArtifactKind.FILE, 1, releaseStorage, false, true),
                new Definition("runtime-summaries", PersistenceArtifactKind.FILE, 1, runtimeSummaryStorage, false, true),
                new Definition("optimization-work-items", PersistenceArtifactKind.FILE, 1, optimizationWorkItemStorage, false, true),
                new Definition("optimization-experiments", PersistenceArtifactKind.FILE, 1, optimizationExperimentStorage, false, true),
                new Definition("optimization-observations", PersistenceArtifactKind.FILE, 1, optimizationObservationStorage, false, true),
                new Definition("optimization-assessments", PersistenceArtifactKind.FILE, 1, optimizationAssessmentStorage, false, true),
                new Definition("operations-metrics", PersistenceArtifactKind.FILE, 1, operationsMetricsStorage, false, true),
                new Definition("packages", PersistenceArtifactKind.DIRECTORY, 1, packageStorage, false, true));
    }

    record Definition(
            String artifactId,
            PersistenceArtifactKind kind,
            int schemaVersion,
            String storage,
            boolean critical,
            boolean includeInSnapshot) {
    }

    private static Path configuredRootPath(String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalArgumentException("configuredRoot must not be blank");
        }
        return Path.of(configuredRoot.trim()).toAbsolutePath().normalize();
    }
}
