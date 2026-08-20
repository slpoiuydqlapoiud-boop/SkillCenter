package com.huawei.skillcenter.analytics;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    private final ActorResolver actorResolver;

    public AnalyticsController(AnalyticsService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/overview")
    ResponseEntity<ApiResponse<AnalyticsOverview>> overview(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String clientType,
            HttpServletRequest request) {
        AnalyticsQuery query = new AnalyticsQueryParser().parse(range, from, to, skillId, teamId, clientType, null);
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.overview(query, actor),
                String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }
}
