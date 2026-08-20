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

@RestController
@RequestMapping("/api/v1/admin/operations")
public class OperationsMetricsController {
    private final OperationsMetricsService metricsService;
    private final ActorResolver actorResolver;

    public OperationsMetricsController(OperationsMetricsService metricsService, ActorResolver actorResolver) {
        this.metricsService = metricsService;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/metrics")
    ResponseEntity<ApiResponse<OperationsMetricsSnapshot>> metrics(
            @RequestParam(defaultValue = "15m") String window,
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) {
            throw new ForbiddenException("Only admin can read operations metrics");
        }
        OperationsWindow parsed;
        try {
            parsed = OperationsWindow.parse(window);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOperationsQueryException(exception.getMessage());
        }
        return ResponseEntity.ok(new ApiResponse<>(metricsService.snapshot(parsed),
                String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }
}
