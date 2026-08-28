package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.ForbiddenException;
import com.huawei.skillcenter.operations.OptimizationWorkItemStaleness;
import com.huawei.skillcenter.operations.OptimizationWorkItemStalenessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/quality/optimization-work-items")
public class OptimizationWorkItemController {
    private final OptimizationWorkItemService service;
    private final ActorResolver actorResolver;
    private final OptimizationWorkItemStalenessService stalenessService;

    public OptimizationWorkItemController(OptimizationWorkItemService service, ActorResolver actorResolver) {
        this(service, actorResolver, null);
    }

    @Autowired
    public OptimizationWorkItemController(OptimizationWorkItemService service, ActorResolver actorResolver,
                                          OptimizationWorkItemStalenessService stalenessService) {
        this.service = service;
        this.actorResolver = actorResolver;
        this.stalenessService = stalenessService;
    }

    @PostMapping
    ResponseEntity<ApiResponse<OptimizationWorkItem>> create(@RequestBody OptimizationWorkItemCreateRequest request,
                                                              HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        OptimizationWorkItem item = service.create(request, actor, requestId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(item, requestId(httpRequest)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<OptimizationWorkItem>>> list(
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String sourceVersion,
            HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.list(skillId, status, ownerId, sourceVersion, actor), requestId(httpRequest)));
    }

    @GetMapping("/health")
    ResponseEntity<ApiResponse<OptimizationWorkItemStaleness>> health(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        OptimizationWorkItemStaleness value = stalenessService == null
                ? new OptimizationWorkItemStaleness("NOT_READY", "OPTIMIZATION_WORK_ITEM_HEALTH_UNAVAILABLE",
                java.time.Instant.now(), 604800, 0, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), List.of())
                : stalenessService.health();
        return ResponseEntity.ok(new ApiResponse<>(value, requestId(httpRequest)));
    }

    @GetMapping("/{workItemId}")
    ResponseEntity<ApiResponse<OptimizationWorkItem>> find(@PathVariable String workItemId,
                                                            HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.find(workItemId, actor), requestId(httpRequest)));
    }

    @PatchMapping("/{workItemId}/status")
    ResponseEntity<ApiResponse<OptimizationWorkItem>> transition(@PathVariable String workItemId,
                                                                  @RequestBody OptimizationWorkItemStatusRequest request,
                                                                  HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.transition(workItemId, request, actor, requestId(httpRequest)), requestId(httpRequest)));
    }

    @PutMapping("/{workItemId}/evidence")
    ResponseEntity<ApiResponse<OptimizationWorkItem>> bindEvidence(@PathVariable String workItemId,
                                                                    @RequestBody OptimizationWorkItemEvidenceRequest request,
                                                                    HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.bindEvidence(workItemId, request, actor, requestId(httpRequest)), requestId(httpRequest)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) throw new ForbiddenException("Only admin can manage optimization work items");
        return actor;
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
