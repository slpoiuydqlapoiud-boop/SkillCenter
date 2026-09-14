package com.huawei.skillcenter.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "skill-center.persistence")
public class PersistenceControlProperties {
    private String backend = "json";
    private String qualityEvidenceBackend = "json";
    private String benchmarkBackend = "json";
    private String releaseBackend = "json";
    private String optimizationWorkItemBackend = "json";
    private String optimizationExperimentBackend = "json";
    private String productionEvidenceBackend = "json";
    private String governanceBackend = "json";
    private String executionEnvironmentBackend = "json";
    private String skillScopeBackend = "json";
    private String skillRelationBackend = "json";
    private String searchIndexBackend = "json";
    private String runtimeSummaryBackend = "json";
    private String operationsMetricsStorage = "./data/operations/metrics.json";
    @Value("${skill-center.quality-evidence-backend:}")
    private String applicationQualityEvidenceBackend = "";
    @Value("${skill-center.benchmark-backend:}")
    private String applicationBenchmarkBackend = "";
    @Value("${skill-center.release-backend:}")
    private String applicationReleaseBackend = "";
    @Value("${skill-center.optimization-work-item-backend:}")
    private String applicationOptimizationWorkItemBackend = "";
    @Value("${skill-center.optimization-experiment-backend:}")
    private String applicationOptimizationExperimentBackend = "";
    @Value("${skill-center.production-evidence-backend:}")
    private String applicationProductionEvidenceBackend = "";
    @Value("${skill-center.governance-backend:}")
    private String applicationGovernanceBackend = "";
    @Value("${skill-center.execution-environment-backend:}")
    private String applicationExecutionEnvironmentBackend = "";
    @Value("${skill-center.skill-scope-backend:}")
    private String applicationSkillScopeBackend = "";
    @Value("${skill-center.skill-relation-backend:}")
    private String applicationSkillRelationBackend = "";
    @Value("${skill-center.search-index-backend:}")
    private String applicationSearchIndexBackend = "";
    @Value("${skill-center.runtime-summary-backend:}")
    private String applicationRuntimeSummaryBackend = "";
    @Value("${skill-center.operations.metrics-storage:}")
    private String applicationOperationsMetricsStorage = "";
    @Value("${skill-center.search-index-events.enabled:false}")
    private String applicationSearchIndexEventsEnabled = "false";
    @Value("${skill-center.search-index-events.retention.scheduler-enabled:false}")
    private String applicationSearchIndexEventRetentionSchedulerEnabled = "false";
    private String controlStorage = "./data/control";
    private String snapshotStorage = "./data/backups";
    private String startupMode = "fail-closed";
    private int manifestRetention = 10;

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend == null ? "json" : backend.trim();
    }

    public String getQualityEvidenceBackend() {
        return qualityEvidenceBackend;
    }

    public void setQualityEvidenceBackend(String qualityEvidenceBackend) {
        this.qualityEvidenceBackend = qualityEvidenceBackend == null ? "json" : qualityEvidenceBackend.trim();
    }

    public String getBenchmarkBackend() {
        return benchmarkBackend;
    }

    public void setBenchmarkBackend(String benchmarkBackend) {
        this.benchmarkBackend = benchmarkBackend == null ? "json" : benchmarkBackend.trim();
    }

    public String getReleaseBackend() {
        return releaseBackend;
    }

    public void setReleaseBackend(String releaseBackend) {
        this.releaseBackend = releaseBackend == null ? "json" : releaseBackend.trim();
    }

    public String getOptimizationWorkItemBackend() {
        return optimizationWorkItemBackend;
    }

    public void setOptimizationWorkItemBackend(String optimizationWorkItemBackend) {
        this.optimizationWorkItemBackend = optimizationWorkItemBackend == null ? "json" : optimizationWorkItemBackend.trim();
    }

    public String getOptimizationExperimentBackend() {
        return optimizationExperimentBackend;
    }

    public void setOptimizationExperimentBackend(String optimizationExperimentBackend) {
        this.optimizationExperimentBackend = optimizationExperimentBackend == null ? "json" : optimizationExperimentBackend.trim();
    }

    public String getProductionEvidenceBackend() {
        return productionEvidenceBackend;
    }

    public void setProductionEvidenceBackend(String productionEvidenceBackend) {
        this.productionEvidenceBackend = productionEvidenceBackend == null ? "json" : productionEvidenceBackend.trim();
    }

    public String getGovernanceBackend() {
        return governanceBackend;
    }

    public void setGovernanceBackend(String governanceBackend) {
        this.governanceBackend = governanceBackend == null ? "json" : governanceBackend.trim();
    }

    public String getExecutionEnvironmentBackend() {
        return executionEnvironmentBackend;
    }

    public void setExecutionEnvironmentBackend(String executionEnvironmentBackend) {
        this.executionEnvironmentBackend = executionEnvironmentBackend == null ? "json" : executionEnvironmentBackend.trim();
    }

    public String getSkillScopeBackend() {
        return skillScopeBackend;
    }

    public void setSkillScopeBackend(String skillScopeBackend) {
        this.skillScopeBackend = skillScopeBackend == null ? "json" : skillScopeBackend.trim();
    }

    public String getSkillRelationBackend() {
        return skillRelationBackend;
    }

    public void setSkillRelationBackend(String skillRelationBackend) {
        this.skillRelationBackend = skillRelationBackend == null ? "json" : skillRelationBackend.trim();
    }

    public void setRuntimeSummaryBackend(String runtimeSummaryBackend) {
        this.runtimeSummaryBackend = runtimeSummaryBackend == null ? "json" : runtimeSummaryBackend.trim();
    }

    public void setOperationsMetricsStorage(String operationsMetricsStorage) {
        this.operationsMetricsStorage = operationsMetricsStorage == null
                ? "./data/operations/metrics.json" : operationsMetricsStorage.trim();
    }

    public String getSearchIndexBackend() {
        return searchIndexBackend;
    }

    public void setSearchIndexBackend(String searchIndexBackend) {
        this.searchIndexBackend = searchIndexBackend == null ? "json" : searchIndexBackend.trim();
    }

    public String getControlStorage() {
        return controlStorage;
    }

    public void setControlStorage(String controlStorage) {
        this.controlStorage = controlStorage == null ? "" : controlStorage.trim();
    }

    public String getSnapshotStorage() {
        return snapshotStorage;
    }

    public void setSnapshotStorage(String snapshotStorage) {
        this.snapshotStorage = snapshotStorage == null ? "" : snapshotStorage.trim();
    }

    public String getStartupMode() {
        return startupMode;
    }

    public void setStartupMode(String startupMode) {
        this.startupMode = startupMode == null ? "" : startupMode.trim();
    }

    public int getManifestRetention() {
        return manifestRetention;
    }

    public void setManifestRetention(int manifestRetention) {
        this.manifestRetention = manifestRetention;
    }

    public Path controlStoragePath(Path configuredParent) {
        return normalizeRoot(configuredParent, controlStorage, "controlStorage");
    }

    public Path snapshotStoragePath(Path configuredParent) {
        return normalizeRoot(configuredParent, snapshotStorage, "snapshotStorage");
    }

    public void validate(Path configuredParent) {
        if (!isSupportedBackend(normalizedBackend())) {
            throw new IllegalArgumentException("backend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedQualityEvidenceBackend())) {
            throw new IllegalArgumentException("qualityEvidenceBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedBenchmarkBackend())) {
            throw new IllegalArgumentException("benchmarkBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedReleaseBackend())) {
            throw new IllegalArgumentException("releaseBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedOptimizationWorkItemBackend())) {
            throw new IllegalArgumentException("optimizationWorkItemBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedOptimizationExperimentBackend())) {
            throw new IllegalArgumentException("optimizationExperimentBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedProductionEvidenceBackend())) {
            throw new IllegalArgumentException("productionEvidenceBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedGovernanceBackend())) {
            throw new IllegalArgumentException("governanceBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedExecutionEnvironmentBackend())) {
            throw new IllegalArgumentException("executionEnvironmentBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedSkillScopeBackend())) {
            throw new IllegalArgumentException("skillScopeBackend must be json, mysql or postgresql");
        }
        if (!isSupportedBackend(normalizedSkillRelationBackend())) {
            throw new IllegalArgumentException("skillRelationBackend must be json, mysql or postgresql");
        }
        if (!isSupportedSearchBackend(normalizedSearchIndexBackend())) {
            throw new IllegalArgumentException("searchIndexBackend must be json, mysql, postgresql or opensearch");
        }
        if ("postgresql".equals(normalizedQualityEvidenceBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("qualityEvidenceBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedBenchmarkBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("benchmarkBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedReleaseBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("releaseBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedOptimizationWorkItemBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("optimizationWorkItemBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedOptimizationExperimentBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("optimizationExperimentBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedProductionEvidenceBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("productionEvidenceBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedGovernanceBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("governanceBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedExecutionEnvironmentBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("executionEnvironmentBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedSkillScopeBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("skillScopeBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedSkillRelationBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("skillRelationBackend=postgresql requires backend=postgresql");
        }
        if ("postgresql".equals(normalizedSearchIndexBackend())
                && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("searchIndexBackend=postgresql requires backend=postgresql");
        }
        if (searchIndexEventsEnabled() && !"postgresql".equals(normalizedBackend())) {
            throw new IllegalArgumentException("search-index-events.enabled requires persistence backend=postgresql");
        }
        if (searchIndexEventRetentionSchedulerEnabled() && !searchIndexEventsEnabled()) {
            throw new IllegalArgumentException(
                    "search-index-events.retention.scheduler-enabled requires search-index-events.enabled");
        }
        if (manifestRetention < 1) {
            throw new IllegalArgumentException("manifestRetention must be at least 1");
        }
        controlStoragePath(configuredParent);
        snapshotStoragePath(configuredParent);
    }

    public String normalizedBackend() {
        return normalizeBackendValue(backend);
    }

    public String normalizedQualityEvidenceBackend() {
        String configured = applicationQualityEvidenceBackend == null || applicationQualityEvidenceBackend.isBlank()
                ? qualityEvidenceBackend
                : applicationQualityEvidenceBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedBenchmarkBackend() {
        String configured = applicationBenchmarkBackend == null || applicationBenchmarkBackend.isBlank()
                ? benchmarkBackend
                : applicationBenchmarkBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedReleaseBackend() {
        String configured = applicationReleaseBackend == null || applicationReleaseBackend.isBlank()
                ? releaseBackend
                : applicationReleaseBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedOptimizationWorkItemBackend() {
        String configured = applicationOptimizationWorkItemBackend == null || applicationOptimizationWorkItemBackend.isBlank()
                ? optimizationWorkItemBackend
                : applicationOptimizationWorkItemBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedOptimizationExperimentBackend() {
        String configured = applicationOptimizationExperimentBackend == null || applicationOptimizationExperimentBackend.isBlank()
                ? optimizationExperimentBackend
                : applicationOptimizationExperimentBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedProductionEvidenceBackend() {
        String configured = applicationProductionEvidenceBackend == null || applicationProductionEvidenceBackend.isBlank()
                ? productionEvidenceBackend
                : applicationProductionEvidenceBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedGovernanceBackend() {
        String configured = applicationGovernanceBackend == null || applicationGovernanceBackend.isBlank()
                ? governanceBackend
                : applicationGovernanceBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedExecutionEnvironmentBackend() {
        String configured = applicationExecutionEnvironmentBackend == null || applicationExecutionEnvironmentBackend.isBlank()
                ? executionEnvironmentBackend
                : applicationExecutionEnvironmentBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedSkillScopeBackend() {
        String configured = applicationSkillScopeBackend == null || applicationSkillScopeBackend.isBlank()
                ? skillScopeBackend : applicationSkillScopeBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedSkillRelationBackend() {
        String configured = applicationSkillRelationBackend == null || applicationSkillRelationBackend.isBlank()
                ? skillRelationBackend : applicationSkillRelationBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedSearchIndexBackend() {
        String configured = applicationSearchIndexBackend == null || applicationSearchIndexBackend.isBlank()
                ? searchIndexBackend : applicationSearchIndexBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedRuntimeSummaryBackend() {
        String configured = applicationRuntimeSummaryBackend == null || applicationRuntimeSummaryBackend.isBlank()
                ? runtimeSummaryBackend : applicationRuntimeSummaryBackend;
        return normalizeBackendValue(configured);
    }

    public String normalizedOperationsMetricsStorage() {
        String configured = applicationOperationsMetricsStorage == null || applicationOperationsMetricsStorage.isBlank()
                ? operationsMetricsStorage : applicationOperationsMetricsStorage;
        return configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
    }

    public boolean searchIndexEventsEnabled() {
        return Boolean.parseBoolean(applicationSearchIndexEventsEnabled == null
                ? "false" : applicationSearchIndexEventsEnabled.trim());
    }

    public boolean searchIndexEventRetentionSchedulerEnabled() {
        return Boolean.parseBoolean(applicationSearchIndexEventRetentionSchedulerEnabled == null
                ? "false" : applicationSearchIndexEventRetentionSchedulerEnabled.trim());
    }

    private boolean isSupportedBackend(String value) {
        return "json".equals(value) || "mysql".equals(value) || "postgresql".equals(value);
    }

    private boolean isSupportedSearchBackend(String value) {
        return isSupportedBackend(value) || "opensearch".equals(value);
    }

    public static String normalizeBackendValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Path normalizeRoot(Path configuredParent, String configuredValue, String fieldName) {
        if (configuredValue == null || configuredValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        Path parent = configuredParent == null
                ? Path.of("").toAbsolutePath().normalize()
                : configuredParent.toAbsolutePath().normalize();
        Path candidate = Path.of(configuredValue.trim());
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : parent.resolve(candidate).normalize();
        if (!resolved.startsWith(parent)) {
            throw new IllegalArgumentException(fieldName + " must remain within the configured parent");
        }
        return resolved;
    }
}
