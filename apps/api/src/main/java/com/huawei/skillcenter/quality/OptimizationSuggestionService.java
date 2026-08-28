package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.operations.RuntimeOperationsQuery;
import com.huawei.skillcenter.operations.RuntimeOperationsService;
import com.huawei.skillcenter.operations.RuntimeOperationsSnapshot;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OptimizationSuggestionService {
    private final QualityEvaluationService qualityEvaluationService;
    private final RuntimeOperationsService runtimeOperationsService;
    private final OptimizationSuggestionCalculator calculator;
    private final OptimizationSuggestionDispositionStore dispositionStore;
    private final OptimizationSuggestionThresholdsStore thresholdsStore;
    private final GovernanceStore governanceStore;
    private final BenchmarkService benchmarkService;

    @Autowired
    public OptimizationSuggestionService(QualityEvaluationService qualityEvaluationService,
                                         RuntimeOperationsService runtimeOperationsService,
                                         OptimizationSuggestionDispositionStore dispositionStore,
                                         OptimizationSuggestionThresholdsStore thresholdsStore,
                                         GovernanceStore governanceStore,
                                         BenchmarkService benchmarkService) {
        this(qualityEvaluationService, runtimeOperationsService, new OptimizationSuggestionCalculator(),
                dispositionStore, thresholdsStore, governanceStore, benchmarkService);
    }

    OptimizationSuggestionService(QualityEvaluationService qualityEvaluationService,
                                   RuntimeOperationsService runtimeOperationsService,
                                   OptimizationSuggestionDispositionStore dispositionStore,
                                   OptimizationSuggestionThresholdsStore thresholdsStore,
                                   GovernanceStore governanceStore) {
        this(qualityEvaluationService, runtimeOperationsService, new OptimizationSuggestionCalculator(),
                dispositionStore, thresholdsStore, governanceStore, null);
    }

    OptimizationSuggestionService(QualityEvaluationService qualityEvaluationService,
                                   RuntimeOperationsService runtimeOperationsService,
                                   OptimizationSuggestionCalculator calculator,
                                   OptimizationSuggestionDispositionStore dispositionStore,
                                   OptimizationSuggestionThresholdsStore thresholdsStore,
                                   GovernanceStore governanceStore) {
        this(qualityEvaluationService, runtimeOperationsService, calculator, dispositionStore, thresholdsStore,
                governanceStore, null);
    }

    OptimizationSuggestionService(QualityEvaluationService qualityEvaluationService,
                                   RuntimeOperationsService runtimeOperationsService,
                                   OptimizationSuggestionCalculator calculator,
                                   OptimizationSuggestionDispositionStore dispositionStore,
                                   OptimizationSuggestionThresholdsStore thresholdsStore,
                                   GovernanceStore governanceStore,
                                   BenchmarkService benchmarkService) {
        this.qualityEvaluationService = qualityEvaluationService;
        this.runtimeOperationsService = runtimeOperationsService;
        this.calculator = calculator;
        this.dispositionStore = dispositionStore;
        this.thresholdsStore = thresholdsStore;
        this.governanceStore = governanceStore;
        this.benchmarkService = benchmarkService;
    }

    public List<OptimizationSuggestion> suggestions(String skillId, String version,
                                                     RuntimeOperationsWindow window, String dataSource) {
        return suggestions(skillId, version, window, dataSource, null, null, null);
    }

    public List<OptimizationSuggestion> suggestions(String skillId, String version,
                                                     RuntimeOperationsWindow window, String dataSource,
                                                     String runtimeId, String mcpServerId, String llmProviderId) {
        java.util.stream.Stream<QualitySnapshot> snapshotStream = qualityEvaluationService
                .snapshots(skillId, dataSource, runtimeId, mcpServerId, llmProviderId).stream();
        QualitySnapshot latest = snapshotStream
                .filter(snapshot -> version == null || version.isBlank() || version.equals(snapshot.skillVersion()))
                .findFirst()
                .orElse(null);
        String resolvedVersion = version == null || version.isBlank()
                ? latest == null ? "latest" : latest.skillVersion() : version;
        RuntimeOperationsSnapshot runtime = runtimeOperationsService.snapshot(
                new RuntimeOperationsQuery(window, skillId, resolvedVersion, null, dataSource, null,
                        runtimeId, mcpServerId, llmProviderId));
        List<OptimizationSuggestionDisposition> dispositions = dispositionStore.findAll(skillId, resolvedVersion);
        List<OptimizationSuggestion> baseSuggestions = calculator.calculate(skillId, resolvedVersion, latest, runtime, thresholdsStore.get());
        List<OptimizationSuggestion> benchmarkSuggestions = hasEnvironmentFilter(runtimeId, mcpServerId, llmProviderId)
                ? List.of() : benchmarkSuggestions(skillId, resolvedVersion);
        return java.util.stream.Stream.concat(baseSuggestions.stream(), benchmarkSuggestions.stream())
                .map(suggestion -> dispositions.stream()
                        .filter(disposition -> disposition.suggestionId().equals(suggestion.id()))
                        .findFirst()
                        .map(disposition -> suggestion.withDisposition(disposition, evidenceAvailable(disposition)))
                        .orElse(suggestion))
                .toList();
    }

    private boolean evidenceAvailable(OptimizationSuggestionDisposition disposition) {
        if (disposition == null || OptimizationSuggestionDisposition.NONE.equals(disposition.evidenceType())) {
            return true;
        }
        return switch (disposition.evidenceType()) {
            case OptimizationSuggestionDisposition.EVALUATION_RUN -> qualityEvaluationService.find(disposition.evidenceId()) != null;
            case OptimizationSuggestionDisposition.QUALITY_SNAPSHOT -> qualityEvaluationService.findSnapshot(disposition.evidenceId()) != null;
            case OptimizationSuggestionDisposition.BENCHMARK -> benchmarkService != null
                    && benchmarkService.list(null).stream()
                    .anyMatch(item -> disposition.evidenceId().equals(item.benchmarkId()));
            default -> false;
        };
    }

    private boolean hasEnvironmentFilter(String runtimeId, String mcpServerId, String llmProviderId) {
        return (runtimeId != null && !runtimeId.isBlank())
                || (mcpServerId != null && !mcpServerId.isBlank())
                || (llmProviderId != null && !llmProviderId.isBlank());
    }

    private List<OptimizationSuggestion> benchmarkSuggestions(String skillId, String resolvedVersion) {
        if (benchmarkService == null || resolvedVersion == null || resolvedVersion.isBlank()) {
            return List.of();
        }
        return benchmarkService.list(skillId).stream()
                .filter(result -> resolvedVersion.equals(result.candidateVersion()))
                .findFirst()
                .filter(result -> result.conclusion().equals("REGRESSED") || result.conclusion().equals("MIXED"))
                .map(result -> {
                    QualityComparison comparison = result.comparison();
                    List<String> evidence = new java.util.ArrayList<>();
                    evidence.add("benchmarkId=" + result.benchmarkId());
                    evidence.add("baselineVersion=" + result.baselineVersion());
                    evidence.add("candidateVersion=" + result.candidateVersion());
                    evidence.add("conclusion=" + result.conclusion());
                    if (comparison != null && comparison.delta() != null) {
                        evidence.add("scoreDelta=" + comparison.delta().score());
                        evidence.add("p95DeltaMs=" + comparison.delta().p95Ms());
                    }
                    String severity = result.conclusion().equals("REGRESSED") ? "HIGH" : "MEDIUM";
                    String title = result.conclusion().equals("REGRESSED") ? "Benchmark 验证发现效果回归" : "Benchmark 验证发现效果权衡";
                    String action = result.conclusion().equals("REGRESSED")
                            ? "暂停候选版本发布，检查回归用例并与基线版本复核后再决定是否回滚。"
                            : "拆分质量分、通过率和延迟差异，确认权衡是否符合业务目标后再发布。";
                    return new OptimizationSuggestion("benchmark-effect", skillId, resolvedVersion, severity,
                            "BENCHMARK", title, evidence, action);
                })
                .map(List::of)
                .orElseGet(List::of);
    }

    public OptimizationSuggestion updateDisposition(String skillId, String version, String suggestionId,
                                                     RuntimeOperationsWindow window, String dataSource,
                                                     OptimizationSuggestionDispositionRequest request,
                                                     Actor actor, String requestId) {
        return updateDisposition(skillId, version, suggestionId, window, dataSource, null, null, null,
                request, actor, requestId);
    }

    public OptimizationSuggestion updateDisposition(String skillId, String version, String suggestionId,
                                                     RuntimeOperationsWindow window, String dataSource,
                                                     String runtimeId, String mcpServerId, String llmProviderId,
                                                     OptimizationSuggestionDispositionRequest request,
                                                     Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("admin"));
        if (request == null) {
            throw new IllegalArgumentException("disposition request must not be null");
        }
        String status = request.normalizedStatus();
        String note = request.normalizedNote();
        String evidenceType = request.normalizedEvidenceType();
        String evidenceId = request.normalizedEvidenceId();
        List<OptimizationSuggestion> currentSuggestions = suggestions(skillId, version, window, dataSource,
                runtimeId, mcpServerId, llmProviderId);
        OptimizationSuggestion current = currentSuggestions.stream()
                .filter(suggestion -> suggestion.id().equals(suggestionId))
                .findFirst()
                .orElseThrow(() -> new OptimizationSuggestionNotFoundException(skillId, version, suggestionId));
        String resolvedVersion = current.version();
        validateEvidence(evidenceType, evidenceId, skillId, resolvedVersion, current);
        OptimizationSuggestionDisposition disposition = new OptimizationSuggestionDisposition(
                skillId, resolvedVersion, suggestionId, status, note, actor.userId(), actor.role(), Instant.now(),
                evidenceType, evidenceId);
        dispositionStore.upsert(disposition);
        governanceStore.addAudit(new AuditEvent(UUID.randomUUID().toString(),
                "OPTIMIZATION_SUGGESTION_DISPOSITION_UPDATED", "SKILL_QUALITY_SUGGESTION",
                skillId + ":" + resolvedVersion + ":" + suggestionId, actor.userId(), actor.role(), requestId,
                disposition.updatedAt(), Map.of("suggestionId", suggestionId, "version", resolvedVersion,
                "status", status, "category", current.category(), "severity", current.severity(),
                "evidenceType", evidenceType, "evidenceId", evidenceId)));
        return current.withDisposition(disposition);
    }

    private void validateEvidence(String evidenceType, String evidenceId, String skillId, String version,
                                  OptimizationSuggestion suggestion) {
        if (OptimizationSuggestionDisposition.NONE.equals(evidenceType)) {
            return;
        }
        switch (evidenceType) {
            case OptimizationSuggestionDisposition.EVALUATION_RUN -> {
                EvaluationRun run = qualityEvaluationService.find(evidenceId);
                if (run == null || run.status() != EvaluationRunStatus.COMPLETED
                        || !skillId.equals(run.skillId()) || !version.equals(run.skillVersion())) {
                    throw new IllegalArgumentException("evidence does not match skill/version or is not completed");
                }
            }
            case OptimizationSuggestionDisposition.QUALITY_SNAPSHOT -> {
                QualitySnapshot snapshot = qualityEvaluationService.findSnapshot(evidenceId);
                if (snapshot == null || !skillId.equals(snapshot.skillId()) || !version.equals(snapshot.skillVersion())) {
                    throw new IllegalArgumentException("evidence does not match skill/version");
                }
            }
            case OptimizationSuggestionDisposition.BENCHMARK -> {
                if (benchmarkService == null) {
                    throw new IllegalArgumentException("benchmark evidence is unavailable");
                }
                BenchmarkResult benchmark = benchmarkService.list(skillId).stream()
                        .filter(item -> evidenceId.equals(item.benchmarkId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("benchmark evidence was not found"));
                if (!skillId.equals(benchmark.skillId())
                        || (!version.equals(benchmark.baselineVersion()) && !version.equals(benchmark.candidateVersion()))
                        || ("BENCHMARK".equals(suggestion.category()) && !version.equals(benchmark.candidateVersion()))) {
                    throw new IllegalArgumentException("evidence does not match skill/version");
                }
            }
            default -> throw new IllegalArgumentException("unsupported evidence type");
        }
    }
}
