package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/governance/organization-directory")
public class OrganizationDirectoryController {
    private final ActorResolver actorResolver;
    private final OrganizationDirectorySyncService service;

    public OrganizationDirectoryController(ActorResolver actorResolver, OrganizationDirectorySyncService service) {
        this.actorResolver = actorResolver;
        this.service = service;
    }

    @GetMapping
    ResponseEntity<ApiResponse<OrganizationDirectoryStatus>> status(HttpServletRequest request) {
        requireAdmin(request);
        return ok(service.status(), request);
    }

    @PostMapping("/sync")
    ResponseEntity<ApiResponse<OrganizationDirectoryStatus>> sync(HttpServletRequest request) {
        Actor actor = requireAdmin(request);
        return ok(service.sync(actor, requestId(request)), request);
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("admin"));
        return actor;
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(data, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
