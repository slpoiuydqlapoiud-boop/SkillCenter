package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.packageupload.PackageSecurityReadiness;
import com.huawei.skillcenter.packageupload.PackageSecurityScanCoordinator;
import com.huawei.skillcenter.packageupload.ResumableUploadStore;
import com.huawei.skillcenter.governance.OrganizationDirectoryStatus;
import com.huawei.skillcenter.governance.OrganizationDirectorySyncService;
import com.huawei.skillcenter.persistence.PersistenceControlService;
import com.huawei.skillcenter.quality.ExternalProviderCatalog;
import com.huawei.skillcenter.quality.ProviderDescriptor;
import com.huawei.skillcenter.quality.ProviderReadinessSummary;
import com.huawei.skillcenter.quality.ProviderRegistry;
import com.huawei.skillcenter.release.ReleaseTargetConnectivityProbeService;
import com.huawei.skillcenter.release.ReleaseTargetProbeResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aggregates existing readiness signals into one fail-closed production handoff view. */
@Service
public class PlatformReadinessService implements ProductionReadinessGate {
    private static final String SCOPE = "PRODUCTION_HANDOFF";
    private final PersistenceControlService persistence;
    private final ProviderRegistry providers;
    private final PackageSecurityScanCoordinator security;
    private final ProductionEvidenceService evidence;
    private final ArtifactStorageReadinessService artifactStorage;
    private final OrganizationDirectorySyncService organizationDirectory;
    private final ReleaseTargetConnectivityProbeService releaseTarget;
    private final RuntimeSummaryBackendHealth runtimeSummary;
    private final OperationsAlertStateHealth alertState;
    private final OperationsMetricsService metrics;
    private OptimizationWorkItemBackendHealth optimizationWorkItem;
    private OptimizationExperimentBackendHealth optimizationExperiment;
    private BenchmarkBackendHealth benchmark;
    private SkillAssetBackendHealth skillAssets;
    private ResumableUploadStore resumableUploads;
    private final Clock clock;

    @Autowired
    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    OrganizationDirectorySyncService organizationDirectory,
                                    ReleaseTargetConnectivityProbeService releaseTarget,
                                     ObjectProvider<RuntimeSummaryBackendHealth> runtimeSummaryProvider,
                                     ObjectProvider<OperationsAlertStateHealth> alertStateProvider,
                                     ObjectProvider<OperationsMetricsService> metricsProvider,
                                     ObjectProvider<OptimizationWorkItemBackendHealth> optimizationWorkItemProvider,
                                     ObjectProvider<OptimizationExperimentBackendHealth> optimizationExperimentProvider,
                                     ObjectProvider<BenchmarkBackendHealth> benchmarkProvider,
                                     ObjectProvider<SkillAssetBackendHealth> skillAssetProvider,
                                     ObjectProvider<ResumableUploadStore> resumableUploadProvider) {
        this(persistence, providers, security, evidence, artifactStorage, organizationDirectory, releaseTarget,
                 runtimeSummaryProvider == null ? null : runtimeSummaryProvider.getIfAvailable(),
                 alertStateProvider == null ? null : alertStateProvider.getIfAvailable(),
                 metricsProvider == null ? null : metricsProvider.getIfAvailable(), null, Clock.systemUTC());
        this.optimizationWorkItem = optimizationWorkItemProvider == null
                ? null : optimizationWorkItemProvider.getIfAvailable();
        this.optimizationExperiment = optimizationExperimentProvider == null
                ? null : optimizationExperimentProvider.getIfAvailable();
        this.benchmark = benchmarkProvider == null ? null : benchmarkProvider.getIfAvailable();
        this.skillAssets = skillAssetProvider == null ? null : skillAssetProvider.getIfAvailable();
        this.resumableUploads = resumableUploadProvider == null
                ? null : resumableUploadProvider.getIfAvailable();
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    Clock clock) {
        this(persistence, providers, security, null, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    Clock clock) {
        this(persistence, providers, security, evidence, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    Clock clock) {
        this(persistence, providers, security, evidence, artifactStorage, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    OrganizationDirectorySyncService organizationDirectory,
                                    ReleaseTargetConnectivityProbeService releaseTarget,
                                    Clock clock) {
        this(persistence, providers, security, evidence, artifactStorage, organizationDirectory, releaseTarget,
                null, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    OrganizationDirectorySyncService organizationDirectory,
                                    ReleaseTargetConnectivityProbeService releaseTarget,
                                    RuntimeSummaryBackendHealth runtimeSummary,
                                    Clock clock) {
        this(persistence, providers, security, evidence, artifactStorage, organizationDirectory, releaseTarget,
                runtimeSummary, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    OrganizationDirectorySyncService organizationDirectory,
                                     ReleaseTargetConnectivityProbeService releaseTarget,
                                     RuntimeSummaryBackendHealth runtimeSummary,
                                     OperationsAlertStateHealth alertState,
                                     Clock clock) {
        this(persistence, providers, security, evidence, artifactStorage, organizationDirectory, releaseTarget,
                runtimeSummary, alertState, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    OrganizationDirectorySyncService organizationDirectory,
                                    ReleaseTargetConnectivityProbeService releaseTarget,
                                    RuntimeSummaryBackendHealth runtimeSummary,
                                    OperationsAlertStateHealth alertState,
                                    OperationsMetricsService metrics,
                                    Clock clock) {
        this(persistence, providers, security, evidence, artifactStorage, organizationDirectory, releaseTarget,
                runtimeSummary, alertState, metrics, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    OrganizationDirectorySyncService organizationDirectory,
                                    ReleaseTargetConnectivityProbeService releaseTarget,
                                    RuntimeSummaryBackendHealth runtimeSummary,
                                    OperationsAlertStateHealth alertState,
                                    OperationsMetricsService metrics,
                                    OptimizationExperimentBackendHealth optimizationExperiment,
                                    Clock clock) {
        this.persistence = require(persistence, "persistence");
        this.providers = require(providers, "providers");
        this.security = require(security, "security");
        this.evidence = evidence;
        this.artifactStorage = artifactStorage;
        this.organizationDirectory = organizationDirectory;
        this.releaseTarget = releaseTarget;
        this.runtimeSummary = runtimeSummary;
        this.alertState = alertState;
        this.metrics = metrics;
        this.optimizationExperiment = optimizationExperiment;
        this.benchmark = null;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    RuntimeSummaryBackendHealth runtimeSummary,
                                    Clock clock) {
        this(persistence, providers, security, null, null, null, null, runtimeSummary, null, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    OperationsAlertStateHealth alertState,
                                    Clock clock) {
        this(persistence, providers, security, null, null, null, null, null, alertState, clock);
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ResumableUploadStore resumableUploads,
                                    Clock clock) {
        this(persistence, providers, security, null, null, null, null, null, null, clock);
        this.resumableUploads = resumableUploads;
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    OptimizationWorkItemBackendHealth optimizationWorkItem,
                                    Clock clock) {
        this(persistence, providers, security, null, null, null, null, null, null, clock);
        this.optimizationWorkItem = optimizationWorkItem;
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    BenchmarkBackendHealth benchmark,
                                    Clock clock) {
        this(persistence, providers, security, null, null, null, null, null, null, null, clock);
        this.benchmark = benchmark;
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    SkillAssetBackendHealth skillAssets,
                                    Clock clock) {
        this(persistence, providers, security, null, null, null, null, null, null, null, clock);
        this.skillAssets = skillAssets;
    }

    public PlatformReadinessService(PersistenceControlService persistence,
                                    ProviderRegistry providers,
                                    PackageSecurityScanCoordinator security,
                                    ProductionEvidenceService evidence,
                                    ArtifactStorageReadinessService artifactStorage,
                                    OrganizationDirectorySyncService organizationDirectory,
                                    Clock clock) {
        this(persistence, providers, security, evidence, artifactStorage, organizationDirectory, null, clock);
    }

    @Override
    public PlatformReadiness readiness() {
        Instant checkedAt = clock.instant();
        List<PlatformReadiness.Component> components = new ArrayList<>(List.of(
                persistenceComponent(),
                providerComponent(),
                securityComponent(checkedAt),
                externalEvidenceComponent()));
        if (artifactStorage != null) components.add(artifactStorageComponent());
        if (organizationDirectory != null) components.add(organizationDirectoryComponent());
        if (releaseTarget != null) components.add(releaseTargetComponent());
        if (runtimeSummary != null) components.add(runtimeSummaryComponent());
        if (alertState != null) components.add(alertStateComponent());
        if (metrics != null) components.add(metricsComponent());
        if (optimizationWorkItem != null) components.add(optimizationWorkItemComponent());
        if (optimizationExperiment != null) components.add(optimizationExperimentComponent());
        if (benchmark != null) components.add(benchmarkComponent());
        if (skillAssets != null) components.add(skillAssetComponent());
        if (resumableUploads != null) components.add(resumableUploadComponent());
        List<String> blockingCodes = components.stream()
                .filter(component -> !"READY".equals(component.status()))
                .map(PlatformReadiness.Component::reasonCode)
                .filter(reason -> reason != null && !reason.isBlank())
                .toList();
        String overall = components.stream().anyMatch(component -> "NOT_READY".equals(component.status()))
                ? "NOT_READY"
                : components.stream().anyMatch(component -> "DEGRADED".equals(component.status()))
                ? "DEGRADED" : "READY";
        return new PlatformReadiness(overall, SCOPE, checkedAt, components, blockingCodes);
    }

    private PlatformReadiness.Component artifactStorageComponent() {
        try {
            com.huawei.skillcenter.distribution.ArtifactStorageReadiness readiness = artifactStorage.readiness();
            if (readiness == null) {
                return component("ARTIFACT_STORAGE", "NOT_READY",
                        "ARTIFACT_STORAGE_HEALTH_UNAVAILABLE", "制品存储就绪状态不可用");
            }
            return component("ARTIFACT_STORAGE", readiness.status(), readiness.reasonCode(), readiness.summary());
        } catch (RuntimeException exception) {
            return component("ARTIFACT_STORAGE", "NOT_READY",
                    "ARTIFACT_STORAGE_HEALTH_UNAVAILABLE", "制品存储状态不可用");
        }
    }

    private PlatformReadiness.Component organizationDirectoryComponent() {
        try {
            OrganizationDirectoryStatus status = organizationDirectory.status();
            if (status == null) {
                return component("ORGANIZATION_DIRECTORY", "NOT_READY",
                        "ORGANIZATION_DIRECTORY_STATUS_UNAVAILABLE", "组织目录状态不可用");
            }
            if ("LOCAL".equals(status.status())) {
                return component("ORGANIZATION_DIRECTORY", "DEGRADED",
                        "ORGANIZATION_DIRECTORY_LOCAL_ONLY", "当前使用本地组织成员目录");
            }
            if ("ACTIVE".equals(status.status()) && !status.stale()) {
                return component("ORGANIZATION_DIRECTORY", "READY",
                        "ORGANIZATION_DIRECTORY_ACTIVE", "组织目录快照已就绪");
            }
            return component("ORGANIZATION_DIRECTORY", "NOT_READY",
                    status.reasonCode().isBlank() ? "ORGANIZATION_DIRECTORY_UNAVAILABLE" : status.reasonCode(),
                    "组织目录快照未通过新鲜度或可用性校验");
        } catch (RuntimeException exception) {
            return component("ORGANIZATION_DIRECTORY", "NOT_READY",
                    "ORGANIZATION_DIRECTORY_STATUS_UNAVAILABLE", "组织目录状态不可用");
        }
    }

    private PlatformReadiness.Component releaseTargetComponent() {
        if (releaseTarget == null) {
            return component("RELEASE_TARGET", "NOT_READY", "RELEASE_TARGET_PROBE_UNAVAILABLE",
                    "发布目标连通性探测服务不可用");
        }
        try {
            ReleaseTargetProbeResult probe = releaseTarget.lastProbe();
            if (probe == null) {
                return component("RELEASE_TARGET", "NOT_READY", "RELEASE_TARGET_PROBE_REQUIRED",
                        "尚未形成发布目标连通性证据");
            }
            if ("REACHABLE".equals(probe.status())) {
                return component("RELEASE_TARGET", "READY", "RELEASE_TARGET_PROBE_OK",
                        "发布目标最近一次状态探测可用");
            }
            if ("SKIPPED".equals(probe.status())) {
                return component("RELEASE_TARGET", "NOT_READY", "RELEASE_TARGET_MOCK_ONLY",
                        "当前仅启用 Mock 发布目标");
            }
            String reason = probe.reasonCode() == null || probe.reasonCode().isBlank()
                    ? "RELEASE_TARGET_PROBE_FAILED" : probe.reasonCode();
            return component("RELEASE_TARGET", "NOT_READY", reason,
                    "发布目标连通性证据未通过生产准入");
        } catch (RuntimeException exception) {
            return component("RELEASE_TARGET", "NOT_READY", "RELEASE_TARGET_PROBE_UNAVAILABLE",
                    "发布目标连通性状态不可用");
        }
    }

    private PlatformReadiness.Component runtimeSummaryComponent() {
        try {
            RuntimeSummaryReadiness readiness = runtimeSummary.readiness();
            if (readiness == null) {
                return component("RUNTIME_SUMMARY_STORE", "NOT_READY",
                        "RUNTIME_SUMMARY_STATUS_UNAVAILABLE", "运行摘要存储状态不可用");
            }
            String status = switch (readiness.status()) {
                case "READY" -> "READY";
                case "DEGRADED" -> "DEGRADED";
                default -> "NOT_READY";
            };
            return component("RUNTIME_SUMMARY_STORE", status, readiness.reasonCode(), readiness.summary());
        } catch (RuntimeException exception) {
            return component("RUNTIME_SUMMARY_STORE", "NOT_READY",
                    "RUNTIME_SUMMARY_STATUS_UNAVAILABLE", "运行摘要存储状态不可用");
        }
    }

    private PlatformReadiness.Component alertStateComponent() {
        try {
            OperationsAlertStateReadiness readiness = alertState.readiness();
            if (readiness == null) {
                return component("OPERATIONS_ALERT_STATE", "NOT_READY",
                        "OPERATIONS_ALERT_STATE_STATUS_UNAVAILABLE", "告警状态存储状态不可用");
            }
            String status = switch (readiness.status()) {
                case "READY" -> "READY";
                case "DEGRADED" -> "DEGRADED";
                default -> "NOT_READY";
            };
            return component("OPERATIONS_ALERT_STATE", status, readiness.reasonCode(), readiness.summary());
        } catch (RuntimeException exception) {
            return component("OPERATIONS_ALERT_STATE", "NOT_READY",
                    "OPERATIONS_ALERT_STATE_STATUS_UNAVAILABLE", "告警状态存储状态不可用");
        }
    }

    private PlatformReadiness.Component metricsComponent() {
        try {
            OperationsMetricsReadiness readiness = metrics.readiness();
            if (readiness == null) {
                return component("OPERATIONS_METRICS_STORE", "NOT_READY",
                        "OPERATIONS_METRICS_STATUS_UNAVAILABLE", "运行指标存储状态不可用");
            }
            if ("READY".equals(readiness.status())) {
                return component("OPERATIONS_METRICS_STORE", "READY", readiness.reasonCode(), readiness.summary());
            }
            String reason = readiness.reasonCode() == null || readiness.reasonCode().isBlank()
                    ? "OPERATIONS_METRICS_STORE_UNAVAILABLE" : readiness.reasonCode();
            return component("OPERATIONS_METRICS_STORE",
                    readiness.shared() ? "NOT_READY" : "DEGRADED", reason, readiness.summary());
        } catch (RuntimeException exception) {
            return component("OPERATIONS_METRICS_STORE", "NOT_READY",
                    "OPERATIONS_METRICS_STATUS_UNAVAILABLE", "运行指标存储状态不可用");
        }
    }

    private PlatformReadiness.Component optimizationWorkItemComponent() {
        try {
            OptimizationWorkItemBackendReadiness readiness = optimizationWorkItem.readiness();
            if (readiness == null) {
                return component("OPTIMIZATION_WORK_ITEM_STORE", "NOT_READY",
                        "OPTIMIZATION_WORK_ITEM_READINESS_UNAVAILABLE", "优化工作项持久化状态不可用");
            }
            String status = switch (readiness.status()) {
                case "READY" -> "READY";
                case "DEGRADED" -> "DEGRADED";
                default -> "NOT_READY";
            };
            String reason = readiness.reasonCode() == null || readiness.reasonCode().isBlank()
                    ? "OPTIMIZATION_WORK_ITEM_READINESS_UNAVAILABLE" : readiness.reasonCode();
            return component("OPTIMIZATION_WORK_ITEM_STORE", status, reason, readiness.summary());
        } catch (RuntimeException exception) {
            return component("OPTIMIZATION_WORK_ITEM_STORE", "NOT_READY",
                    "OPTIMIZATION_WORK_ITEM_READINESS_UNAVAILABLE", "优化工作项持久化状态不可用");
        }
    }

    private PlatformReadiness.Component optimizationExperimentComponent() {
        try {
            OptimizationExperimentBackendReadiness readiness = optimizationExperiment.readiness();
            if (readiness == null) {
                return component("OPTIMIZATION_EXPERIMENT_STORE", "NOT_READY",
                        "OPTIMIZATION_EXPERIMENT_READINESS_UNAVAILABLE", "优化实验持久化状态不可用");
            }
            String status = switch (readiness.status()) {
                case "READY" -> "READY";
                case "DEGRADED" -> "DEGRADED";
                default -> "NOT_READY";
            };
            String reason = readiness.reasonCode() == null || readiness.reasonCode().isBlank()
                    ? "OPTIMIZATION_EXPERIMENT_READINESS_UNAVAILABLE" : readiness.reasonCode();
            return component("OPTIMIZATION_EXPERIMENT_STORE", status, reason, readiness.summary());
        } catch (RuntimeException exception) {
            return component("OPTIMIZATION_EXPERIMENT_STORE", "NOT_READY",
                    "OPTIMIZATION_EXPERIMENT_READINESS_UNAVAILABLE", "优化实验持久化状态不可用");
        }
    }

    private PlatformReadiness.Component benchmarkComponent() {
        try {
            BenchmarkBackendReadiness readiness = benchmark.readiness();
            if (readiness == null) {
                return component("BENCHMARK_STORE", "NOT_READY",
                        "BENCHMARK_READINESS_UNAVAILABLE", "Benchmark 持久化状态不可用");
            }
            String status = switch (readiness.status()) {
                case "READY" -> "READY";
                case "DEGRADED" -> "DEGRADED";
                default -> "NOT_READY";
            };
            String reason = readiness.reasonCode() == null || readiness.reasonCode().isBlank()
                    ? "BENCHMARK_READINESS_UNAVAILABLE" : readiness.reasonCode();
            return component("BENCHMARK_STORE", status, reason, readiness.summary());
        } catch (RuntimeException exception) {
            return component("BENCHMARK_STORE", "NOT_READY",
                    "BENCHMARK_READINESS_UNAVAILABLE", "Benchmark 持久化状态不可用");
        }
    }

    private PlatformReadiness.Component skillAssetComponent() {
        try {
            SkillAssetBackendReadiness readiness = skillAssets.readiness();
            if (readiness == null) {
                return component("SKILL_ASSET_STORE", "NOT_READY",
                        "SKILL_ASSET_READINESS_UNAVAILABLE", "Skill 资产持久化状态不可用");
            }
            String status = switch (readiness.status()) {
                case "READY" -> "READY";
                case "DEGRADED" -> "DEGRADED";
                default -> "NOT_READY";
            };
            String reason = readiness.reasonCode() == null || readiness.reasonCode().isBlank()
                    ? "SKILL_ASSET_READINESS_UNAVAILABLE" : readiness.reasonCode();
            return component("SKILL_ASSET_STORE", status, reason, readiness.summary());
        } catch (RuntimeException exception) {
            return component("SKILL_ASSET_STORE", "NOT_READY",
                    "SKILL_ASSET_READINESS_UNAVAILABLE", "Skill 资产持久化状态不可用");
        }
    }

    private PlatformReadiness.Component resumableUploadComponent() {
        try {
            ResumableUploadStore.Readiness readiness = resumableUploads.readiness();
            if (readiness == null) {
                return component("RESUMABLE_UPLOAD_STORE", "NOT_READY",
                        "RESUMABLE_UPLOAD_READINESS_UNAVAILABLE", "可恢复上传存储状态不可用");
            }
            if ("READY".equals(readiness.status())) {
                return component("RESUMABLE_UPLOAD_STORE", "READY",
                        "RESUMABLE_UPLOAD_READY", "可恢复上传共享存储已就绪");
            }
            if ("LOCAL_ONLY".equals(readiness.status())) {
                return component("RESUMABLE_UPLOAD_STORE", "DEGRADED",
                        "RESUMABLE_UPLOAD_LOCAL_ONLY", "可恢复上传仅使用本地临时存储");
            }
            return component("RESUMABLE_UPLOAD_STORE", "NOT_READY",
                    "RESUMABLE_UPLOAD_NOT_READY", "可恢复上传共享存储未就绪");
        } catch (RuntimeException exception) {
            return component("RESUMABLE_UPLOAD_STORE", "NOT_READY",
                    "RESUMABLE_UPLOAD_READINESS_UNAVAILABLE", "可恢复上传存储状态不可用");
        }
    }

    private PlatformReadiness.Component externalEvidenceComponent() {
        if (evidence == null) {
            return component("PRODUCTION_EXTERNAL_EVIDENCE", "NOT_READY",
                    "PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED", "生产外部依赖与上线证据尚未完成核验");
        }
        try {
            ProductionEvidenceReadiness readiness = evidence.evaluate();
            if (readiness == null) {
                return component("PRODUCTION_EXTERNAL_EVIDENCE", "NOT_READY",
                        "PRODUCTION_EVIDENCE_STORE_UNAVAILABLE", "生产交付证据台账不可用");
            }
            String summary = "已核验 " + readiness.acceptedCount() + "/" + readiness.requiredCount() + " 项";
            return component("PRODUCTION_EXTERNAL_EVIDENCE", readiness.status(), readiness.reasonCode(), summary);
        } catch (RuntimeException exception) {
            return component("PRODUCTION_EXTERNAL_EVIDENCE", "NOT_READY",
                    "PRODUCTION_EVIDENCE_STORE_UNAVAILABLE", "生产交付证据台账不可用");
        }
    }

    private PlatformReadiness.Component persistenceComponent() {
        try {
            PersistenceControlService.PersistenceStatusView status = persistence.status();
            String overall = status == null ? "FAIL_CLOSED" : status.overall();
            if ("READY".equals(overall)) {
                return component("PERSISTENCE_CONTROL_PLANE", "READY",
                        "PERSISTENCE_CONTROL_PLANE_READY", "持久化控制面校验通过");
            }
            String reason = firstReason(status == null ? List.of() : status.controlPlaneReasonCodes());
            if ("DEGRADED".equals(overall)) {
                return component("PERSISTENCE_CONTROL_PLANE", "DEGRADED",
                        reason.isBlank() ? "PERSISTENCE_CONTROL_PLANE_DEGRADED" : reason,
                        "持久化控制面存在降级项");
            }
            return component("PERSISTENCE_CONTROL_PLANE", "NOT_READY",
                    reason.isBlank() ? "PERSISTENCE_CONTROL_PLANE_FAIL_CLOSED" : reason,
                    "持久化控制面未通过准入校验");
        } catch (RuntimeException exception) {
            return component("PERSISTENCE_CONTROL_PLANE", "NOT_READY",
                    "PERSISTENCE_CONTROL_PLANE_UNAVAILABLE", "持久化控制面状态不可用");
        }
    }

    private PlatformReadiness.Component providerComponent() {
        try {
            ProviderReadinessSummary summary = providerSummary(providers.list());
            if ("READY".equals(summary.status())) {
                return component("PROVIDER_GRAPH", "READY", summary.reason(), "活动 Provider 图已就绪");
            }
            return component("PROVIDER_GRAPH", "NOT_READY", summary.reason(), summary.reasonDescription());
        } catch (RuntimeException exception) {
            return component("PROVIDER_GRAPH", "NOT_READY",
                    "PROVIDER_GRAPH_UNAVAILABLE", "Provider readiness 状态不可用");
        }
    }

    private PlatformReadiness.Component securityComponent(Instant checkedAt) {
        try {
            PackageSecurityReadiness readiness = security.readiness(checkedAt);
            if (readiness != null && "REQUIRED".equals(readiness.mode())
                    && "READY".equals(readiness.status())
                    && readiness.missingCapabilities().isEmpty()) {
                return component("PACKAGE_SECURITY_GATE", "READY", readiness.reasonCode(),
                        "外部包安全扫描准入已就绪");
            }
            String reason = readiness == null ? "EXTERNAL_SECURITY_SCANNER_UNAVAILABLE" : readiness.reasonCode();
            return component("PACKAGE_SECURITY_GATE", "NOT_READY", reason,
                    "外部包安全扫描准入未就绪");
        } catch (RuntimeException exception) {
            return component("PACKAGE_SECURITY_GATE", "NOT_READY",
                    "EXTERNAL_SECURITY_SCANNER_UNAVAILABLE", "外部包安全扫描状态不可用");
        }
    }

    private ProviderReadinessSummary providerSummary(List<ProviderDescriptor> values) {
        List<ProviderDescriptor> active = values == null ? List.of() : values;
        Set<String> activeIds = new HashSet<>();
        active.stream().filter(provider -> provider != null).map(ProviderDescriptor::id).forEach(activeIds::add);
        long healthy = active.stream().filter(provider -> provider != null && "UP".equals(provider.status())).count();
        long notConfigured = active.stream()
                .filter(provider -> provider != null && "NOT_CONFIGURED".equals(provider.status())).count();
        long activeContractOnly = active.stream()
                .filter(provider -> provider != null && "CONTRACT_ONLY".equals(provider.status())).count();
        long reservedContractOnly = ExternalProviderCatalog.list().stream()
                .filter(provider -> "CONTRACT_ONLY".equals(provider.status()))
                .filter(provider -> !activeIds.contains(provider.id())).count();
        long contractOnly = activeContractOnly + reservedContractOnly;
        List<String> contractOnlyIds = new ArrayList<>();
        active.stream().filter(provider -> provider != null && "CONTRACT_ONLY".equals(provider.status()))
                .map(ProviderDescriptor::id).forEach(contractOnlyIds::add);
        ExternalProviderCatalog.list().stream().filter(provider -> "CONTRACT_ONLY".equals(provider.status()))
                .filter(provider -> !activeIds.contains(provider.id()))
                .map(provider -> provider.id()).forEach(contractOnlyIds::add);
        List<String> notConfiguredIds = active.stream()
                .filter(provider -> provider != null && "NOT_CONFIGURED".equals(provider.status()))
                .map(ProviderDescriptor::id).sorted().toList();
        String status = notConfigured > 0 ? "DEGRADED" : contractOnly > 0 ? "PARTIAL" : "READY";
        String reason = notConfigured > 0 ? "ACTIVE_PROVIDER_NOT_CONFIGURED"
                : contractOnly > 0 ? "EXTERNAL_PROVIDERS_CONTRACT_ONLY" : "ALL_PROVIDERS_READY";
        return new ProviderReadinessSummary(status, active.size(), (int) healthy,
                (int) contractOnly, (int) notConfigured, reason, null, contractOnlyIds, notConfiguredIds);
    }

    private PlatformReadiness.Component component(String id, String status, String reason, String summary) {
        return new PlatformReadiness.Component(id, status, reason, summary);
    }

    private String firstReason(List<String> reasons) {
        return reasons == null || reasons.isEmpty() || reasons.get(0) == null ? "" : reasons.get(0).trim();
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
