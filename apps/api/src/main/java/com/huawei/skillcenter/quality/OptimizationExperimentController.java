package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/quality/optimization-experiments")
public class OptimizationExperimentController {
    private final OptimizationExperimentService service;
    private final OptimizationExperimentObservationService observationService;
    private final OptimizationExperimentAssessmentService assessmentService;
    private final ActorResolver actorResolver;

    @Autowired
    public OptimizationExperimentController(OptimizationExperimentService service,
                                            OptimizationExperimentObservationService observationService,
                                            OptimizationExperimentAssessmentService assessmentService,
                                            ActorResolver actorResolver) {
        this.service = service;
        this.observationService = observationService;
        this.assessmentService = assessmentService;
        this.actorResolver = actorResolver;
    }

    public OptimizationExperimentController(OptimizationExperimentService service,
                                            OptimizationExperimentObservationService observationService,
                                            ActorResolver actorResolver) {
        this(service, observationService, null, actorResolver);
    }

    public OptimizationExperimentController(OptimizationExperimentService service, ActorResolver actorResolver) {
        this(service, null, null, actorResolver);
    }

    @PostMapping
    ResponseEntity<ApiResponse<OptimizationExperiment>> create(
            @RequestBody OptimizationExperimentCreateRequest request, HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(service.create(request, actor, requestId(httpRequest)), requestId(httpRequest)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<OptimizationExperiment>>> list(
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String workItemId,
            @RequestParam(required = false) String status,
            HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.list(skillId, workItemId, status, actor), requestId(httpRequest)));
    }

    @GetMapping("/{experimentId}")
    ResponseEntity<ApiResponse<OptimizationExperiment>> find(@PathVariable String experimentId,
                                                               HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.find(experimentId, actor), requestId(httpRequest)));
    }

    @PostMapping("/{experimentId}/reconcile")
    ResponseEntity<ApiResponse<OptimizationExperiment>> reconcile(@PathVariable String experimentId,
                                                                   HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.reconcile(experimentId, actor, requestId(httpRequest)),
                requestId(httpRequest)));
    }

    @PostMapping("/{experimentId}/cancel")
    ResponseEntity<ApiResponse<OptimizationExperiment>> cancel(@PathVariable String experimentId,
                                                                HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.cancel(experimentId, actor, requestId(httpRequest)),
                requestId(httpRequest)));
    }

    @PostMapping("/{experimentId}/benchmark")
    ResponseEntity<ApiResponse<OptimizationExperiment>> benchmark(
            @PathVariable String experimentId,
            @RequestBody(required = false) OptimizationExperimentBenchmarkRequest request,
            HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.benchmark(experimentId, request, actor, requestId(httpRequest)),
                requestId(httpRequest)));
    }

    @PostMapping("/{experimentId}/decision")
    ResponseEntity<ApiResponse<OptimizationExperiment>> decide(@PathVariable String experimentId,
                                                                HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.decide(experimentId, actor, requestId(httpRequest)),
                requestId(httpRequest)));
    }

    @GetMapping("/{experimentId}/decision")
    ResponseEntity<ApiResponse<OptimizationExperimentDecision>> decision(@PathVariable String experimentId,
                                                                          HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.findDecision(experimentId, actor), requestId(httpRequest)));
    }

    @GetMapping("/{experimentId}/observations")
    ResponseEntity<ApiResponse<List<OptimizationExperimentObservation>>>
    observations(@PathVariable String experimentId, HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(observationService.list(experimentId, actor), requestId(httpRequest)));
    }

    @PostMapping("/{experimentId}/observations")
    ResponseEntity<ApiResponse<OptimizationExperimentObservation>> observe(
            @PathVariable String experimentId,
            @RequestBody(required = false) OptimizationExperimentObservationRequest request,
            HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(observationService.capture(
                experimentId, request, actor, requestId(httpRequest)), requestId(httpRequest)));
    }

    @GetMapping("/{experimentId}/assessments")
    ResponseEntity<ApiResponse<List<OptimizationExperimentAssessment>>>
    assessments(@PathVariable String experimentId, HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(assessmentService.list(experimentId, actor), requestId(httpRequest)));
    }

    @GetMapping("/{experimentId}/assessments/{assessmentId}")
    ResponseEntity<ApiResponse<OptimizationExperimentAssessment>> assessment(
            @PathVariable String experimentId, @PathVariable String assessmentId, HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        OptimizationExperimentAssessment result = assessmentService.find(assessmentId, actor);
        if (!experimentId.equals(result.experimentId())) {
            throw new OptimizationExperimentAssessmentNotFoundException(assessmentId);
        }
        return ResponseEntity.ok(new ApiResponse<>(result, requestId(httpRequest)));
    }

    @PostMapping("/{experimentId}/assessments")
    ResponseEntity<ApiResponse<OptimizationExperimentAssessment>> assess(
            @PathVariable String experimentId,
            @RequestBody OptimizationExperimentAssessmentRequest request,
            HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(assessmentService.assess(
                experimentId, request, actor, requestId(httpRequest)), requestId(httpRequest)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) throw new ForbiddenException("Only admin can manage optimization experiments");
        return actor;
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
