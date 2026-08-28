package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import com.huawei.skillcenter.operations.InvalidOperationsQueryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills/{skillId}/quality")
public class SkillQualityController {
    private final QualityComparisonService service;
    private final OptimizationSuggestionService optimizationSuggestionService;
    private final ActorResolver actorResolver;
    private final BenchmarkService benchmarkService;

    public SkillQualityController(QualityComparisonService service,
                                  OptimizationSuggestionService optimizationSuggestionService,
                                  ActorResolver actorResolver,
                                  BenchmarkService benchmarkService) {
        this.service = service;
        this.optimizationSuggestionService = optimizationSuggestionService;
        this.actorResolver = actorResolver;
        this.benchmarkService = benchmarkService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<SkillQualityDetail>> detail(
            @PathVariable String skillId,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String runtimeId,
            @RequestParam(required = false) String mcpServerId,
            @RequestParam(required = false) String llmProviderId,
            HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(service.detail(skillId, version, parseWindow(window), dataSource,
                runtimeId, mcpServerId, llmProviderId), requestId(request)));
    }

    @GetMapping("/compare")
    ResponseEntity<ApiResponse<QualityComparison>> compare(
            @PathVariable String skillId,
            @RequestParam String baselineVersion,
            @RequestParam String candidateVersion,
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String runtimeId,
            @RequestParam(required = false) String mcpServerId,
            @RequestParam(required = false) String llmProviderId,
            @RequestParam(required = false) String suiteId,
            @RequestParam(required = false) String suiteVersion,
            HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(service.compare(skillId, baselineVersion, candidateVersion,
                parseWindow(window), dataSource, runtimeId, mcpServerId, llmProviderId, suiteId, suiteVersion), requestId(request)));
    }

    @GetMapping("/suggestions")
    ResponseEntity<ApiResponse<java.util.List<OptimizationSuggestion>>> suggestions(
            @PathVariable String skillId,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String runtimeId,
            @RequestParam(required = false) String mcpServerId,
            @RequestParam(required = false) String llmProviderId,
            HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(optimizationSuggestionService.suggestions(
                skillId, version, parseWindow(window), dataSource, runtimeId, mcpServerId, llmProviderId), requestId(request)));
    }

    @GetMapping("/benchmarks")
    ResponseEntity<ApiResponse<java.util.List<BenchmarkResult>>> benchmarks(
            @PathVariable String skillId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String runtimeId,
            @RequestParam(required = false) String mcpServerId,
            @RequestParam(required = false) String llmProviderId,
            @RequestParam(required = false) String suiteId,
            @RequestParam(required = false) String suiteVersion,
            HttpServletRequest request) {
        java.util.List<BenchmarkResult> values = benchmarkService.list(skillId, dataSource, runtimeId, mcpServerId,
                llmProviderId, suiteId, suiteVersion).stream()
                .filter(result -> version == null || version.isBlank()
                        || version.equals(result.candidateVersion()) || version.equals(result.baselineVersion()))
                .toList();
        return ResponseEntity.ok(new ApiResponse<>(values, requestId(request)));
    }

    @PatchMapping("/suggestions/{suggestionId}/disposition")
    ResponseEntity<ApiResponse<OptimizationSuggestion>> updateDisposition(
            @PathVariable String skillId,
            @PathVariable String suggestionId,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String runtimeId,
            @RequestParam(required = false) String mcpServerId,
            @RequestParam(required = false) String llmProviderId,
            @RequestBody OptimizationSuggestionDispositionRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(optimizationSuggestionService.updateDisposition(
                skillId, version, suggestionId, parseWindow(window), dataSource,
                runtimeId, mcpServerId, llmProviderId, body, actorResolver.resolve(request), requestId(request)), requestId(request)));
    }

    private RuntimeOperationsWindow parseWindow(String value) {
        try {
            return RuntimeOperationsWindow.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOperationsQueryException(exception.getMessage());
        }
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
