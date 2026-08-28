package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.ForbiddenException;
import com.huawei.skillcenter.governance.GovernanceStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/quality")
public class QualityEvaluationController {
    private final QualityEvaluationService service;
    private final ActorResolver actorResolver;
    private final ProviderRegistry providerRegistry;
    private final ProviderContractVerificationService providerContractVerificationService;
    private final OptimizationSuggestionThresholdsStore thresholdsStore;
    private final GovernanceStore governanceStore;

    public QualityEvaluationController(QualityEvaluationService service, ActorResolver actorResolver,
                                       ProviderRegistry providerRegistry,
                                       ProviderContractVerificationService providerContractVerificationService,
                                       OptimizationSuggestionThresholdsStore thresholdsStore,
                                       GovernanceStore governanceStore) {
        this.service = service;
        this.actorResolver = actorResolver;
        this.providerRegistry = providerRegistry;
        this.providerContractVerificationService = providerContractVerificationService;
        this.thresholdsStore = thresholdsStore;
        this.governanceStore = governanceStore;
    }

    @PostMapping("/evaluations")
    ResponseEntity<ApiResponse<EvaluationRun>> submit(@RequestBody EvaluationRequest request,
                                                       HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        EvaluationRun run = service.submit(request);
        return ResponseEntity.accepted().body(new ApiResponse<>(run, requestId(httpRequest)));
    }

    @GetMapping("/evaluations")
    ResponseEntity<ApiResponse<List<EvaluationRun>>> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String skillId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String dataSource,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String runtimeId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String mcpServerId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String llmProviderId,
            HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.list(skillId, dataSource, runtimeId, mcpServerId, llmProviderId), requestId(httpRequest)));
    }

    @GetMapping("/providers")
    ResponseEntity<ApiResponse<List<ProviderDescriptor>>> providers(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(providerRegistry.list(), requestId(httpRequest)));
    }

    @GetMapping("/provider-contracts")
    ResponseEntity<ApiResponse<List<ExternalProviderDescriptor>>> providerContracts(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(ExternalProviderCatalog.list(), requestId(httpRequest)));
    }

    @GetMapping("/provider-contract-verification")
    ResponseEntity<ApiResponse<List<ProviderContractVerification>>> providerContractVerification(
            HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(providerContractVerificationService.verify(), requestId(httpRequest)));
    }

    @GetMapping("/provider-readiness")
    ResponseEntity<ApiResponse<ProviderReadinessSummary>> providerReadiness(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        List<ProviderDescriptor> active = providerRegistry.list();
        java.util.Set<String> activeProviderIds = active.stream()
                .map(ProviderDescriptor::id)
                .collect(java.util.stream.Collectors.toSet());
        long healthy = active.stream().filter(provider -> "UP".equals(provider.status())).count();
        long notConfigured = active.stream().filter(provider -> "NOT_CONFIGURED".equals(provider.status())).count();
        long activeContractOnly = active.stream().filter(provider -> "CONTRACT_ONLY".equals(provider.status())).count();
        long reservedContractOnly = ExternalProviderCatalog.list().stream()
                .filter(provider -> "CONTRACT_ONLY".equals(provider.status()))
                .filter(provider -> !activeProviderIds.contains(provider.id())).count();
        long contractOnly = activeContractOnly + reservedContractOnly;
        List<String> contractOnlyProviderIds = new java.util.ArrayList<>();
        active.stream().filter(provider -> "CONTRACT_ONLY".equals(provider.status()))
                .map(ProviderDescriptor::id).forEach(contractOnlyProviderIds::add);
        ExternalProviderCatalog.list().stream()
                .filter(provider -> "CONTRACT_ONLY".equals(provider.status()))
                .filter(provider -> !activeProviderIds.contains(provider.id()))
                .map(ExternalProviderDescriptor::id).forEach(contractOnlyProviderIds::add);
        contractOnlyProviderIds.sort(String::compareTo);
        List<String> notConfiguredProviderIds = active.stream()
                .filter(provider -> "NOT_CONFIGURED".equals(provider.status()))
                .map(ProviderDescriptor::id)
                .sorted()
                .toList();
        String status = notConfigured > 0 ? "DEGRADED" : contractOnly > 0 ? "PARTIAL" : "READY";
        String reason = notConfigured > 0 ? "ACTIVE_PROVIDER_NOT_CONFIGURED"
                : contractOnly > 0 ? "EXTERNAL_PROVIDERS_CONTRACT_ONLY" : "ALL_PROVIDERS_READY";
        ProviderReadinessSummary summary = new ProviderReadinessSummary(status, active.size(), (int) healthy,
                (int) contractOnly, (int) notConfigured, reason, null, contractOnlyProviderIds,
                notConfiguredProviderIds);
        return ResponseEntity.ok(new ApiResponse<>(summary, requestId(httpRequest)));
    }

    @GetMapping("/suites")
    ResponseEntity<ApiResponse<List<EvaluationSuite>>> suites(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.listSuites(), requestId(httpRequest)));
    }

    @PostMapping("/suites")
    ResponseEntity<ApiResponse<EvaluationSuite>> createSuite(@RequestBody EvaluationSuiteRequest request,
                                                              HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(new ApiResponse<>(service.createSuite(request), requestId(httpRequest)));
    }

    @GetMapping("/rules")
    ResponseEntity<ApiResponse<QualityRuleSet>> rules(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.rules(), requestId(httpRequest)));
    }

    @org.springframework.web.bind.annotation.PutMapping("/rules")
    ResponseEntity<ApiResponse<QualityRuleSet>> updateRules(@RequestBody QualityRuleSet rules,
                                                             HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.updateRules(rules), requestId(httpRequest)));
    }

    @GetMapping("/suggestion-thresholds")
    ResponseEntity<ApiResponse<OptimizationSuggestionThresholds>> suggestionThresholds(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(thresholdsStore.get(), requestId(httpRequest)));
    }

    @org.springframework.web.bind.annotation.PutMapping("/suggestion-thresholds")
    ResponseEntity<ApiResponse<OptimizationSuggestionThresholds>> updateSuggestionThresholds(
            @RequestBody OptimizationSuggestionThresholds thresholds, HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        OptimizationSuggestionThresholds updated = thresholdsStore.update(thresholds);
        governanceStore.addAudit(new com.huawei.skillcenter.governance.AuditEvent(
                UUID.randomUUID().toString(), "OPTIMIZATION_SUGGESTION_THRESHOLDS_UPDATED", "QUALITY_CONFIGURATION",
                "optimization-suggestions", actor.userId(), actor.role(), requestId(httpRequest), Instant.now(),
                Map.of("minSuccessRatePercent", String.valueOf(updated.minSuccessRatePercent()),
                        "maxP95Ms", String.valueOf(updated.maxP95Ms()),
                        "minRuntimeSamples", String.valueOf(updated.minRuntimeSamples()))));
        return ResponseEntity.ok(new ApiResponse<>(updated, requestId(httpRequest)));
    }

    @GetMapping("/evaluations/{runId}")
    ResponseEntity<ApiResponse<EvaluationRun>> find(@PathVariable String runId, HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.find(runId), requestId(httpRequest)));
    }

    @PostMapping("/evaluations/{runId}/cancel")
    ResponseEntity<ApiResponse<EvaluationRun>> cancel(@PathVariable String runId, HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        EvaluationRun run = service.cancel(runId);
        governanceStore.addAudit(new com.huawei.skillcenter.governance.AuditEvent(
                UUID.randomUUID().toString(), run.status() == EvaluationRunStatus.CANCELLED
                        ? "QUALITY_EVALUATION_CANCELLED" : "QUALITY_EVALUATION_CANCEL_REQUESTED",
                "QUALITY_EVALUATION", run.id(),
                actor.userId(), actor.role(), requestId(httpRequest), Instant.now(),
                Map.of("skillId", run.skillId(), "skillVersion", run.skillVersion(), "status", run.status().name())));
        return ResponseEntity.ok(new ApiResponse<>(run, requestId(httpRequest)));
    }

    @GetMapping("/evaluations/{runId}/snapshot")
    ResponseEntity<ApiResponse<QualitySnapshot>> snapshot(@PathVariable String runId,
                                                           HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.snapshot(runId), requestId(httpRequest)));
    }

    @GetMapping("/evaluations/{runId}/results")
    ResponseEntity<ApiResponse<List<EvaluationCaseResult>>> results(@PathVariable String runId,
                                                                     HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.results(runId), requestId(httpRequest)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) {
            throw new ForbiddenException("Only admin can manage quality evaluations");
        }
        return actor;
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
