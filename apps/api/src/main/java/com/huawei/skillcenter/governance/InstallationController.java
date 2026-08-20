package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/installations")
public class InstallationController {
    private final InstallationService service;
    private final ActorResolver actorResolver;

    public InstallationController(InstallationService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<InstallationRecord>>> list(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.list(actor), requestId(request)));
    }

    @GetMapping("/{installationId}")
    ResponseEntity<ApiResponse<InstallationRecord>> get(@PathVariable String installationId,
                                                          HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.get(installationId, actor), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
