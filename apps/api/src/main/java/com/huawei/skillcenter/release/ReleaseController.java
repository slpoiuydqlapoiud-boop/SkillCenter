package com.huawei.skillcenter.release;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/releases")
public class ReleaseController {
    private final ReleaseService service;
    private final ReleaseAdmissionService admissionService;
    private final ActorResolver actorResolver;

    public ReleaseController(ReleaseService service, ActorResolver actorResolver) {
        this(service, null, actorResolver);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReleaseController(ReleaseService service, ReleaseAdmissionService admissionService,
                             ActorResolver actorResolver) {
        this.service = service;
        this.admissionService = admissionService;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/admission")
    ResponseEntity<ApiResponse<ReleaseAdmissionDecision>> admission(
            @RequestParam String skillId, @RequestParam String version, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("reviewer", "admin"));
        if (admissionService == null) {
            throw new IllegalStateException("Release admission service is unavailable");
        }
        return ResponseEntity.ok(new ApiResponse<>(admissionService.evaluate(skillId, version), requestId(request)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<ReleaseRecord>>> list(
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) ReleaseEnvironment targetEnvironment,
            @RequestParam(required = false) ReleaseStatus status,
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.list(skillId, version, targetEnvironment, status, actor), requestId(request)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<ReleaseRecord>> create(@RequestBody ReleaseRequest body, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        ReleaseRecord release = service.request(body, actor, requestId(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(release, requestId(request)));
    }

    @GetMapping("/{releaseId}")
    ResponseEntity<ApiResponse<ReleaseRecord>> find(@PathVariable String releaseId, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(service.find(releaseId, actorResolver.resolve(request)), requestId(request)));
    }

    @PostMapping("/{releaseId}/approve")
    ResponseEntity<ApiResponse<ReleaseRecord>> approve(@PathVariable String releaseId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.approve(releaseId, actor, requestId(request)), requestId(request)));
    }

    @PostMapping("/{releaseId}/reject")
    ResponseEntity<ApiResponse<ReleaseRecord>> reject(@PathVariable String releaseId,
                                                       @RequestBody ReleaseRejectionRequest body,
                                                       HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.reject(releaseId, body, actor, requestId(request)), requestId(request)));
    }

    @PostMapping("/{releaseId}/promote")
    ResponseEntity<ApiResponse<ReleaseRecord>> promote(@PathVariable String releaseId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.promote(releaseId, actor, requestId(request)), requestId(request)));
    }

    @PostMapping("/{releaseId}/rollback-review")
    ResponseEntity<ApiResponse<ReleaseRecord>> rollbackReview(@PathVariable String releaseId,
                                                               @RequestBody RollbackReviewRequest body,
                                                               HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.rollbackReview(releaseId, body, actor, requestId(request)), requestId(request)));
    }

    @PostMapping("/{releaseId}/rollback")
    ResponseEntity<ApiResponse<ReleaseRecord>> rollback(@PathVariable String releaseId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.rollback(releaseId, actor, requestId(request)), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
