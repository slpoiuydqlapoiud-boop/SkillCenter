package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/quality/compatibility-matrices")
public class CompatibilityMatrixController {
    private final CompatibilityMatrixService service;
    private final ActorResolver actorResolver;

    public CompatibilityMatrixController(CompatibilityMatrixService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @PostMapping
    ResponseEntity<ApiResponse<CompatibilityMatrixRun>> create(@RequestBody CompatibilityMatrixCreateRequest request,
                                                                 HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        CompatibilityMatrixRun run = service.create(request, actor, requestId(httpRequest));
        return ResponseEntity.accepted().body(new ApiResponse<>(run, requestId(httpRequest)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<CompatibilityMatrixRun>>> list(
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String skillVersion,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dataSource,
            HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.list(skillId, skillVersion, status, dataSource, actor),
                requestId(httpRequest)));
    }

    @GetMapping("/{matrixRunId}")
    ResponseEntity<ApiResponse<CompatibilityMatrixRun>> find(@PathVariable String matrixRunId,
                                                               HttpServletRequest httpRequest) {
        return ResponseEntity.ok(new ApiResponse<>(service.find(matrixRunId, requireAdmin(httpRequest)), requestId(httpRequest)));
    }

    @GetMapping("/{matrixRunId}/cases")
    ResponseEntity<ApiResponse<List<CompatibilityMatrixCase>>> cases(@PathVariable String matrixRunId,
                                                                       HttpServletRequest httpRequest) {
        return ResponseEntity.ok(new ApiResponse<>(service.cases(matrixRunId, requireAdmin(httpRequest)), requestId(httpRequest)));
    }

    @PostMapping("/{matrixRunId}/cancel")
    ResponseEntity<ApiResponse<CompatibilityMatrixRun>> cancel(@PathVariable String matrixRunId,
                                                                HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.cancel(matrixRunId, actor, requestId(httpRequest)), requestId(httpRequest)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) {
            throw new ForbiddenException("Only admin can manage compatibility matrices");
        }
        return actor;
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
