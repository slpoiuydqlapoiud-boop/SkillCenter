package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/quality/provider-readiness")
public class ProviderConnectivityProbeController {
    private final ActorResolver actorResolver;
    private final ProviderConnectivityProbeService service;

    public ProviderConnectivityProbeController(ActorResolver actorResolver,
                                                ProviderConnectivityProbeService service) {
        this.actorResolver = actorResolver;
        this.service = service;
    }

    @PostMapping("/probe")
    ResponseEntity<ApiResponse<List<ProviderProbeResult>>> probe(
            @RequestParam(required = false) String providerId,
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, java.util.Set.of("admin"));
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return ResponseEntity.ok(new ApiResponse<>(service.probe(providerId, actor, requestId), requestId));
    }
}
