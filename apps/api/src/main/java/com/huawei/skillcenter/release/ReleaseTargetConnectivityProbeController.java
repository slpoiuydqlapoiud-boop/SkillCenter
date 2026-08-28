package com.huawei.skillcenter.release;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only endpoint for explicit release target connectivity evidence. */
@RestController
@RequestMapping("/api/v1/admin/platform/release-target")
public class ReleaseTargetConnectivityProbeController {
    private final ActorResolver actorResolver;
    private final ReleaseTargetConnectivityProbeService service;

    public ReleaseTargetConnectivityProbeController(ActorResolver actorResolver,
                                                    ReleaseTargetConnectivityProbeService service) {
        this.actorResolver = actorResolver;
        this.service = service;
    }

    @PostMapping("/probe")
    ResponseEntity<ApiResponse<ReleaseTargetProbeResult>> probe(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return ResponseEntity.ok(new ApiResponse<>(service.probe(actor, requestId), requestId));
    }

    @GetMapping("/probes")
    ResponseEntity<ApiResponse<List<ReleaseTargetProbeResult>>> history(
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return ResponseEntity.ok(new ApiResponse<>(service.history(actor, limit), requestId));
    }
}
