package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/operations/traces")
public class TraceController {
    private final TraceService service;
    private final ActorResolver actorResolver;

    public TraceController(TraceService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<TraceObservation>>> query(
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String runtimeId,
            @RequestParam(required = false) String mcpServerId,
            @RequestParam(required = false) String llmProviderId,
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) {
            throw new ForbiddenException("Only admin can read traces");
        }
        RuntimeOperationsWindow parsed;
        try {
            parsed = RuntimeOperationsWindow.parse(window);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOperationsQueryException(exception.getMessage());
        }
        TraceQuery query;
        try {
            query = new TraceQuery(parsed, skillId, version, traceId, status, dataSource,
                    runtimeId, mcpServerId, llmProviderId);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOperationsQueryException(exception.getMessage());
        }
        return ResponseEntity.ok(new ApiResponse<>(service.query(query),
                String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }
}
