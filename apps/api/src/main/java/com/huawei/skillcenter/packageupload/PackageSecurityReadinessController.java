package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/package-security")
public class PackageSecurityReadinessController {
    private final PackageSecurityScanCoordinator coordinator;
    private final ActorResolver actorResolver;

    public PackageSecurityReadinessController(PackageSecurityScanCoordinator coordinator, ActorResolver actorResolver) {
        this.coordinator = coordinator;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/readiness")
    ResponseEntity<ApiResponse<PackageSecurityReadiness>> readiness(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("admin"));
        return ResponseEntity.ok(new ApiResponse<>(coordinator.readiness(Instant.now()), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
