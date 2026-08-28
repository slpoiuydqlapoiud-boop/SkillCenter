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

import java.util.Set;

/** Exposes upload backend state without leaking local paths or remote credentials. */
@RestController
@RequestMapping("/api/v1/admin/platform/resumable-uploads")
public class ResumableUploadReadinessController {
    private final ResumableUploadStore store;
    private final ActorResolver actorResolver;

    public ResumableUploadReadinessController(ResumableUploadStore store, ActorResolver actorResolver) {
        this.store = store;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/readiness")
    ResponseEntity<ApiResponse<ResumableUploadStore.Readiness>> readiness(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("admin"));
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return ResponseEntity.ok(new ApiResponse<>(store.readiness(), requestId));
    }
}
