package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.packageupload.PackageSecurityReadiness;
import com.huawei.skillcenter.packageupload.PackageSecurityScanCoordinator;
import com.huawei.skillcenter.packageupload.ResumableUploadStore;
import com.huawei.skillcenter.distribution.ArtifactStorageReadiness;
import com.huawei.skillcenter.governance.OrganizationDirectoryStatus;
import com.huawei.skillcenter.governance.OrganizationDirectorySyncService;
import com.huawei.skillcenter.persistence.PersistenceControlService;
import com.huawei.skillcenter.quality.ProviderDescriptor;
import com.huawei.skillcenter.quality.ProviderRegistry;
import com.huawei.skillcenter.release.ReleaseTargetConnectivityProbeService;
import com.huawei.skillcenter.release.ReleaseTargetProbeResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformReadinessServiceTest {
    private static final Instant CHECKED_AT = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void productionHandoffIsFailClosedUntilExternalEvidenceIsVerified() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of(
                new ProviderDescriptor("runner", "runner", "v1", "UP"),
                new ProviderDescriptor("openclaw-runner", "runner", "v1", "UP"),
                new ProviderDescriptor("deepeval-evaluation", "evaluation", "v1", "UP"),
                new ProviderDescriptor("langfuse-observability", "observability", "v1", "UP")));
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE", "LICENSE"), List.of()));

        PlatformReadinessService service = new PlatformReadinessService(
                persistence, providers, security, Clock.fixed(CHECKED_AT, ZoneOffset.UTC));

        PlatformReadiness readiness = service.readiness();

        assertEquals("NOT_READY", readiness.overall());
        assertEquals("PRODUCTION_HANDOFF", readiness.scope());
        assertEquals(CHECKED_AT, readiness.checkedAt());
        assertEquals("READY", readiness.component("PERSISTENCE_CONTROL_PLANE").status());
        assertEquals("READY", readiness.component("PROVIDER_GRAPH").status());
        assertEquals("READY", readiness.component("PACKAGE_SECURITY_GATE").status());
        assertEquals("NOT_READY", readiness.component("PRODUCTION_EXTERNAL_EVIDENCE").status());
        assertTrue(readiness.blockingReasonCodes().contains("PRODUCTION_EXTERNAL_EVIDENCE_REQUIRED"));
        assertFalse(readiness.toString().contains("credential"));
        assertFalse(readiness.toString().contains("/secret"));
    }

    @Test
    void providerAndSecurityFailuresBecomeStableBlockingSignals() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView(
                "FAIL_CLOSED", List.of(), List.of("PERSISTENCE_ARTIFACT_CORRUPTED")));
        when(providers.list()).thenReturn(List.of(
                new ProviderDescriptor("runner", "runner", "v1", "NOT_CONFIGURED")));
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "DEGRADED", "EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of("LICENSE")));

        PlatformReadinessService service = new PlatformReadinessService(
                persistence, providers, security, Clock.fixed(CHECKED_AT, ZoneOffset.UTC));

        PlatformReadiness readiness = service.readiness();

        assertEquals("NOT_READY", readiness.overall());
        assertEquals("NOT_READY", readiness.component("PERSISTENCE_CONTROL_PLANE").status());
        assertEquals("NOT_READY", readiness.component("PROVIDER_GRAPH").status());
        assertEquals("NOT_READY", readiness.component("PACKAGE_SECURITY_GATE").status());
        assertTrue(readiness.blockingReasonCodes().contains("PERSISTENCE_ARTIFACT_CORRUPTED"));
        assertTrue(readiness.blockingReasonCodes().contains("ACTIVE_PROVIDER_NOT_CONFIGURED"));
        assertTrue(readiness.blockingReasonCodes().contains("EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE"));
    }

    @Test
    void skillAssetBackendReadinessIsIncludedInProductionHandoff() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        SkillAssetBackendHealth skillAssets = mock(SkillAssetBackendHealth.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of(), List.of()));
        when(skillAssets.readiness()).thenReturn(new SkillAssetBackendReadiness(
                "postgresql", "NOT_READY", "SKILL_ASSET_SCHEMA_REQUIRED", "schema missing"));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, skillAssets, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("NOT_READY", readiness.component("SKILL_ASSET_STORE").status());
        assertTrue(readiness.blockingReasonCodes().contains("SKILL_ASSET_SCHEMA_REQUIRED"));
    }

    @Test
    void searchIndexReadinessIsIncludedInProductionHandoff() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        SkillSearchBackendHealth searchIndex = mock(SkillSearchBackendHealth.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of(), List.of()));
        when(searchIndex.readiness()).thenReturn(new SkillSearchBackendReadiness(
                "postgresql", "NOT_READY", "SEARCH_INDEX_SCHEMA_REQUIRED", "schema missing"));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, searchIndex, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("NOT_READY", readiness.component("SKILL_SEARCH_INDEX").status());
        assertTrue(readiness.blockingReasonCodes().contains("SEARCH_INDEX_SCHEMA_REQUIRED"));
    }

    @Test
    void externalEvidenceReadinessIsConsumedInsteadOfStaticBlocking() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        ProductionEvidenceService evidence = mock(ProductionEvidenceService.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of(
                new ProviderDescriptor("runner", "runner", "v1", "UP"),
                new ProviderDescriptor("openclaw-runner", "runner", "v1", "UP"),
                new ProviderDescriptor("deepeval-evaluation", "evaluation", "v1", "UP"),
                new ProviderDescriptor("langfuse-observability", "observability", "v1", "UP")));
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE", "LICENSE"), List.of()));
        when(evidence.evaluate()).thenReturn(new ProductionEvidenceReadiness(
                "READY", "PRODUCTION_EXTERNAL_EVIDENCE_READY", 9, 9, List.of()));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, evidence, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("READY", readiness.overall());
        assertEquals("READY", readiness.component("PRODUCTION_EXTERNAL_EVIDENCE").status());
        assertEquals("PRODUCTION_EXTERNAL_EVIDENCE_READY",
                readiness.component("PRODUCTION_EXTERNAL_EVIDENCE").reasonCode());
    }

    @Test
    void runtimeSummaryReadinessIsExposedAsAProductionBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));

        RuntimeSummaryBackendHealth runtime = () -> new RuntimeSummaryReadiness(
                "json", "DEGRADED", "RUNTIME_SUMMARY_JSON_ONLY", "运行摘要仅使用本地 JSON");

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, runtime, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("RUNTIME_SUMMARY_STORE").status());
        assertTrue(readiness.blockingReasonCodes().contains("RUNTIME_SUMMARY_JSON_ONLY"));
    }

    @Test
    void alertStateReadinessIsExposedAsAProductionBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));

        OperationsAlertStateHealth alertState = () -> new OperationsAlertStateReadiness(
                "memory", "DEGRADED", "OPERATIONS_ALERT_STATE_MEMORY_ONLY", "告警状态仅保存在当前进程");

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, alertState, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("OPERATIONS_ALERT_STATE").status());
        assertTrue(readiness.blockingReasonCodes().contains("OPERATIONS_ALERT_STATE_MEMORY_ONLY"));
    }

    @Test
    void metricsReadinessIsExposedAsAProductionBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        OperationsMetricsService metrics = mock(OperationsMetricsService.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));
        when(metrics.readiness()).thenReturn(new OperationsMetricsReadiness(
                "local", "NOT_READY", false, "OPERATIONS_METRICS_SHARED_STORE_REQUIRED", "指标仅使用本地状态"));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, null, null, null, null,
                null, null, metrics, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("OPERATIONS_METRICS_STORE").status());
        assertEquals("OPERATIONS_METRICS_SHARED_STORE_REQUIRED",
                readiness.component("OPERATIONS_METRICS_STORE").reasonCode());
        assertTrue(readiness.blockingReasonCodes().contains("OPERATIONS_METRICS_SHARED_STORE_REQUIRED"));
    }

    @Test
    void optimizationWorkItemReadinessIsExposedAsAProductionBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));

        OptimizationWorkItemBackendHealth workItems = () -> new OptimizationWorkItemBackendReadiness(
                "json", "DEGRADED", "OPTIMIZATION_WORK_ITEM_JSON_ONLY", "优化工作项仅使用本地 JSON");

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, workItems, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("OPTIMIZATION_WORK_ITEM_STORE").status());
        assertEquals("OPTIMIZATION_WORK_ITEM_JSON_ONLY",
                readiness.component("OPTIMIZATION_WORK_ITEM_STORE").reasonCode());
        assertTrue(readiness.blockingReasonCodes().contains("OPTIMIZATION_WORK_ITEM_JSON_ONLY"));
    }

    @Test
    void optimizationExperimentReadinessIsExposedAsAProductionBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));

        OptimizationExperimentBackendHealth experiments = () -> new OptimizationExperimentBackendReadiness(
                "json", "DEGRADED", "OPTIMIZATION_EXPERIMENT_JSON_ONLY", "优化实验仅使用本地状态");

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, null, null, null, null,
                null, null, null, experiments, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("OPTIMIZATION_EXPERIMENT_STORE").status());
        assertEquals("OPTIMIZATION_EXPERIMENT_JSON_ONLY",
                readiness.component("OPTIMIZATION_EXPERIMENT_STORE").reasonCode());
        assertTrue(readiness.blockingReasonCodes().contains("OPTIMIZATION_EXPERIMENT_JSON_ONLY"));
    }

    @Test
    void benchmarkReadinessIsExposedAsAProductionBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));

        BenchmarkBackendHealth benchmarks = () -> new BenchmarkBackendReadiness(
                "json", "DEGRADED", "BENCHMARK_JSON_ONLY", "Benchmark 仅使用本地状态");

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, benchmarks, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("BENCHMARK_STORE").status());
        assertEquals("BENCHMARK_JSON_ONLY", readiness.component("BENCHMARK_STORE").reasonCode());
        assertTrue(readiness.blockingReasonCodes().contains("BENCHMARK_JSON_ONLY"));
    }

    @Test
    void artifactStorageBackendIsIncludedAsAProductionBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        ProductionEvidenceService evidence = mock(ProductionEvidenceService.class);
        ArtifactStorageReadinessService artifacts = mock(ArtifactStorageReadinessService.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));
        when(evidence.evaluate()).thenReturn(new ProductionEvidenceReadiness(
                "READY", "PRODUCTION_EXTERNAL_EVIDENCE_READY", 9, 9, List.of()));
        when(artifacts.readiness()).thenReturn(new ArtifactStorageReadiness(
                "object-storage", "NOT_READY", "ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED",
                "对象存储适配器尚未配置"));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, evidence, artifacts,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("NOT_READY", readiness.overall());
        assertEquals("NOT_READY", readiness.component("ARTIFACT_STORAGE").status());
        assertTrue(readiness.blockingReasonCodes().contains("ARTIFACT_STORAGE_OBJECT_ADAPTER_NOT_CONFIGURED"));
    }

    @Test
    void localOrganizationDirectoryIsVisibleAsADegradedNonBlockingSignal() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        OrganizationDirectorySyncService directory = mock(OrganizationDirectorySyncService.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));
        when(directory.status()).thenReturn(OrganizationDirectoryStatus.local());

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, null, null, directory,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("ORGANIZATION_DIRECTORY").status());
        assertEquals("ORGANIZATION_DIRECTORY_LOCAL_ONLY",
                readiness.component("ORGANIZATION_DIRECTORY").reasonCode());
        assertEquals("NOT_READY", readiness.overall());
    }

    @Test
    void reachableReleaseTargetProbeIsIncludedInProductionReadiness() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        ProductionEvidenceService evidence = mock(ProductionEvidenceService.class);
        ReleaseTargetConnectivityProbeService releaseTarget = mock(ReleaseTargetConnectivityProbeService.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));
        when(evidence.evaluate()).thenReturn(new ProductionEvidenceReadiness(
                "READY", "PRODUCTION_EXTERNAL_EVIDENCE_READY", 9, 9, List.of()));
        when(releaseTarget.lastProbe()).thenReturn(new ReleaseTargetProbeResult(
                "release-target", "REACHABLE", "PROBE_OK", 204, 12, CHECKED_AT));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, evidence, null, null, releaseTarget,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("READY", readiness.component("RELEASE_TARGET").status());
        assertEquals("RELEASE_TARGET_PROBE_OK", readiness.component("RELEASE_TARGET").reasonCode());
    }

    @Test
    void staleReleaseTargetProbeBlocksProductionReadiness() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        ProductionEvidenceService evidence = mock(ProductionEvidenceService.class);
        ReleaseTargetConnectivityProbeService releaseTarget = mock(ReleaseTargetConnectivityProbeService.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));
        when(evidence.evaluate()).thenReturn(new ProductionEvidenceReadiness(
                "READY", "PRODUCTION_EXTERNAL_EVIDENCE_READY", 9, 9, List.of()));
        when(releaseTarget.lastProbe()).thenReturn(new ReleaseTargetProbeResult(
                "release-target", "STALE", "RELEASE_TARGET_PROBE_EXPIRED", null, 12, CHECKED_AT));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, evidence, null, null, releaseTarget,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("NOT_READY", readiness.component("RELEASE_TARGET").status());
        assertEquals("RELEASE_TARGET_PROBE_EXPIRED", readiness.component("RELEASE_TARGET").reasonCode());
        assertTrue(readiness.blockingReasonCodes().contains("RELEASE_TARGET_PROBE_EXPIRED"));
    }

    @Test
    void resumableUploadBackendIsIncludedInProductionReadiness() {
        PersistenceControlService persistence = mock(PersistenceControlService.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        PackageSecurityScanCoordinator security = mock(PackageSecurityScanCoordinator.class);
        ResumableUploadStore uploads = mock(ResumableUploadStore.class);
        when(persistence.status()).thenReturn(new PersistenceControlService.PersistenceStatusView("READY", List.of()));
        when(providers.list()).thenReturn(List.of());
        when(security.readiness(CHECKED_AT)).thenReturn(new PackageSecurityReadiness(
                "REQUIRED", "READY", "EXTERNAL_SECURITY_SCANNER_READY",
                "scanner", "1.0", CHECKED_AT, List.of("MALWARE"), List.of()));
        when(uploads.readiness()).thenReturn(new ResumableUploadStore.Readiness("local", "LOCAL_ONLY"));

        PlatformReadiness readiness = new PlatformReadinessService(
                persistence, providers, security, uploads, Clock.fixed(CHECKED_AT, ZoneOffset.UTC)).readiness();

        assertEquals("DEGRADED", readiness.component("RESUMABLE_UPLOAD_STORE").status());
        assertEquals("RESUMABLE_UPLOAD_LOCAL_ONLY",
                readiness.component("RESUMABLE_UPLOAD_STORE").reasonCode());
    }
}
