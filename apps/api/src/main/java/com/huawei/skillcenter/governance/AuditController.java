package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final GovernanceStore store;
    private final ActorResolver actorResolver;
    private final AuditProjectionService projectionService;

    public AuditController(GovernanceStore store, ActorResolver actorResolver, AuditProjectionService projectionService) {
        this.store = store;
        this.actorResolver = actorResolver;
        this.projectionService = projectionService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<?>> list(@RequestParam(required = false) String action,
                                        @RequestParam(required = false) String resourceType,
                                        HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        List<AuditEvent> events = store.snapshot().audits().stream()
                .filter(event -> action == null || action.isBlank() || action.equals(event.action()))
                .filter(event -> resourceType == null || resourceType.isBlank() || resourceType.equals(event.resourceType()))
                .toList();
        if ("admin".equals(actor.role())) {
            return ResponseEntity.ok(new ApiResponse<>(events, requestId(request)));
        }
        RoleGuard.require(actor, Set.of("reviewer"));
        return ResponseEntity.ok(new ApiResponse<>(projectionService.auditSummary(events), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
