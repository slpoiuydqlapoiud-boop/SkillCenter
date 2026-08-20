package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.InstallationService;
import com.huawei.skillcenter.security.IdempotencyFingerprint;
import com.huawei.skillcenter.security.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills/{skillId}/installations")
public class DistributionController {
    private final DistributionService service;
    private final InstallationService installationService;
    private final ActorResolver actorResolver;
    private final IdempotencyService idempotencyService;

    public DistributionController(DistributionService service, InstallationService installationService,
                                  ActorResolver actorResolver, IdempotencyService idempotencyService) {
        this.service = service;
        this.installationService = installationService;
        this.actorResolver = actorResolver;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    ResponseEntity<ApiResponse<DistributionResponse>> create(
            @PathVariable String skillId,
            @RequestBody(required = false) InstallationRequest request,
            HttpServletRequest servletRequest) {
        Actor actor = actorResolver.resolve(servletRequest);
        String key = servletRequest.getHeader("Idempotency-Key");
        String fingerprint = IdempotencyFingerprint.sha256(skillId + "\u0000" + String.valueOf(request));
        idempotencyService.claim("INSTALLATION_CREATE", actor.userId(), key, fingerprint);
        try {
            return ResponseEntity.status(201).body(new ApiResponse<>(
                    installationService.createManifest(skillId, request, actor,
                            String.valueOf(servletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))),
                    String.valueOf(servletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
        } catch (RuntimeException exception) {
            idempotencyService.release("INSTALLATION_CREATE", actor.userId(), key, fingerprint);
            throw exception;
        }
    }
}
