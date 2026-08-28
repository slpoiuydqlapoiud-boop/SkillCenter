package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BenchmarkService {
    private final QualityComparisonService comparisonService;
    private final BenchmarkEffectCalculator effectCalculator;
    private final BenchmarkRepository store;
    private final GovernanceStore governanceStore;

    @Autowired
    public BenchmarkService(QualityComparisonService comparisonService, BenchmarkRepository store,
                            GovernanceStore governanceStore) {
        this(comparisonService, new BenchmarkEffectCalculator(), store, governanceStore);
    }

    BenchmarkService(QualityComparisonService comparisonService, BenchmarkEffectCalculator effectCalculator,
                     BenchmarkRepository store, GovernanceStore governanceStore) {
        this.comparisonService = comparisonService;
        this.effectCalculator = effectCalculator;
        this.store = store;
        this.governanceStore = governanceStore;
    }

    public BenchmarkResult run(BenchmarkRequest request, RuntimeOperationsWindow window,
                               Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        validate(request);
        if (request.baselineVersion().equals(request.candidateVersion())) {
            throw new IllegalArgumentException("baselineVersion and candidateVersion must differ");
        }
        BenchmarkResult existing = store.findByExperimentId(request.experimentId());
        if (existing != null) {
            if (!sameExperimentContext(existing, request, window)) {
                throw new IllegalArgumentException("experimentId is already bound to a different benchmark context");
            }
            return existing;
        }
        String dataSource = request.dataSource() == null || request.dataSource().isBlank() ? null : request.dataSource();
        QualityComparison comparison = request.suiteId().isBlank()
                ? comparisonService.compare(request.skillId(), request.baselineVersion(), request.candidateVersion(),
                window, dataSource, request.runtimeId(), request.mcpServerId(), request.llmProviderId())
                : comparisonService.compare(request.skillId(), request.baselineVersion(), request.candidateVersion(),
                window, dataSource, request.runtimeId(), request.mcpServerId(), request.llmProviderId(),
                request.suiteId(), request.suiteVersion());
        BenchmarkResult result = new BenchmarkResult("benchmark-" + UUID.randomUUID(), request.skillId(),
                request.baselineVersion(), request.candidateVersion(), window.label(),
                dataSource == null ? "all" : dataSource, effectCalculator.classify(comparison), Instant.now(), comparison,
                request.runtimeId(), request.mcpServerId(), request.llmProviderId(), request.suiteId(), request.suiteVersion(),
                request.experimentId());
        store.add(result);
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(),
                "QUALITY_BENCHMARK_CREATED", "QUALITY_BENCHMARK", result.benchmarkId(), actor.userId(), actor.role(),
                requestId, result.createdAt(), Map.of("skillId", result.skillId(), "baselineVersion", result.baselineVersion(),
                "candidateVersion", result.candidateVersion(), "conclusion", result.conclusion(), "dataSource", result.dataSource(),
                "suiteId", result.suiteId(), "suiteVersion", result.suiteVersion())));
        return result;
    }

    private boolean sameExperimentContext(BenchmarkResult existing, BenchmarkRequest request,
                                          RuntimeOperationsWindow window) {
        String requestedDataSource = request.dataSource() == null || request.dataSource().isBlank()
                ? "all" : request.dataSource().trim().toLowerCase();
        return existing.skillId().equals(request.skillId())
                && existing.baselineVersion().equals(request.baselineVersion())
                && existing.candidateVersion().equals(request.candidateVersion())
                && existing.window().equals(window.label())
                && existing.dataSource().equals(requestedDataSource)
                && existing.runtimeId().equals(request.runtimeId())
                && existing.mcpServerId().equals(request.mcpServerId())
                && existing.llmProviderId().equals(request.llmProviderId())
                && existing.suiteId().equals(request.suiteId())
                && existing.suiteVersion().equals(request.suiteVersion());
    }

    public List<BenchmarkResult> list(String skillId) {
        return list(skillId, null, null, null, null);
    }

    public List<BenchmarkResult> list(String skillId, String runtimeId, String mcpServerId, String llmProviderId) {
        return list(skillId, null, runtimeId, mcpServerId, llmProviderId);
    }

    public List<BenchmarkResult> list(String skillId, String dataSource, String runtimeId,
                                      String mcpServerId, String llmProviderId) {
        return list(skillId, dataSource, runtimeId, mcpServerId, llmProviderId, null, null);
    }

    public List<BenchmarkResult> list(String skillId, String dataSource, String runtimeId,
                                      String mcpServerId, String llmProviderId,
                                      String suiteId, String suiteVersion) {
        String requestedDataSource = normalizeDataSource(dataSource);
        String requestedRuntimeId = normalizeFilter(runtimeId, "runtimeId");
        String requestedMcpServerId = normalizeFilter(mcpServerId, "mcpServerId");
        String requestedLlmProviderId = normalizeFilter(llmProviderId, "llmProviderId");
        String requestedSuiteId = suiteId == null || suiteId.isBlank() ? null : suiteId.trim();
        String requestedSuiteVersion = suiteVersion == null || suiteVersion.isBlank() ? null : suiteVersion.trim();
        return store.findAll(skillId).stream()
                .filter(result -> requestedDataSource == null || requestedDataSource.equals(result.dataSource()))
                .filter(result -> matches(result.runtimeId(), requestedRuntimeId))
                .filter(result -> matches(result.mcpServerId(), requestedMcpServerId))
                .filter(result -> matches(result.llmProviderId(), requestedLlmProviderId))
                .filter(result -> matches(result.suiteId(), requestedSuiteId))
                .filter(result -> matches(result.suiteVersion(), requestedSuiteVersion))
                .toList();
    }

    private String normalizeDataSource(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.isBlank() || "all".equals(normalized)) return null;
        if (!Set.of("mock", "production").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private boolean matches(String actual, String requested) {
        return requested == null || requested.isBlank() || (actual != null && actual.equals(requested.trim()));
    }

    private String normalizeFilter(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private void validate(BenchmarkRequest request) {
        if (request == null) throw new IllegalArgumentException("benchmark request must not be null");
        require(request.skillId(), "skillId");
        require(request.baselineVersion(), "baselineVersion");
        require(request.candidateVersion(), "candidateVersion");
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
