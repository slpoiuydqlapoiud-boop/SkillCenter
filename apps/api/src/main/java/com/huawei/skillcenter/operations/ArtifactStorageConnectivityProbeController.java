package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/platform/artifact-storage")
public class ArtifactStorageConnectivityProbeController {
    private final ActorResolver actorResolver;
    private final ArtifactStorageConnectivityProbeService service;

    public ArtifactStorageConnectivityProbeController(ActorResolver actorResolver,
                                                      ArtifactStorageConnectivityProbeService service) {
        this.actorResolver = actorResolver;
        this.service = service;
    }

    @PostMapping("/probe")
    ResponseEntity<ApiResponse<com.huawei.skillcenter.distribution.ArtifactStorageProbeResult>> probe(
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return ResponseEntity.ok(new ApiResponse<>(service.probe(actor, requestId), requestId));
    }
}
