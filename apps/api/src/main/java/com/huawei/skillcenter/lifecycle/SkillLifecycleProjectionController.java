package com.huawei.skillcenter.lifecycle;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.release.ReleaseEnvironment;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/skill-lifecycle/projection")
public class SkillLifecycleProjectionController {
    private static final Set<String> ADMIN_ROLE = Set.of("admin");
    private static final Set<String> SUPPORTED_STATUS_CODES = Set.of(
            "PENDING_REVIEW", "SECURITY_REVIEW", "PUBLISHED", "REJECTED",
            "DEPRECATED", "WITHDRAWN", "ACTIVE");

    private final SkillLifecycleProjectionService service;
    private final ActorResolver actorResolver;

    public SkillLifecycleProjectionController(SkillLifecycleProjectionService service,
                                              ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/status")
    ResponseEntity<ApiResponse<SkillLifecycleProjectionStatus>> status(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.status(), requestId(request)));
    }

    @PostMapping("/preflight")
    ResponseEntity<ApiResponse<SkillLifecycleProjectionPreflight>> preflight(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.preflight(), requestId(request)));
    }

    @GetMapping("/reconciliation")
    ResponseEntity<ApiResponse<SkillLifecycleProjectionReconciliation>> reconciliation(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.reconciliation(), requestId(request)));
    }

    @PostMapping("/import")
    ResponseEntity<ApiResponse<SkillLifecycleProjectionImportResult>> importSnapshot(
            @Valid @RequestBody SkillLifecycleProjectionImportRequest body,
            HttpServletRequest request) {
        Actor actor = requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(
                service.importSnapshot(body.sourceSha256(), actor, requestId(request)),
                requestId(request)));
    }

    @GetMapping("/skills")
    ResponseEntity<ApiResponse<List<SkillLifecycleProjectionResponse>>> skills(
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) ReleaseEnvironment environment,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            HttpServletRequest request) {
        Actor actor = requireAdmin(request);
        String normalizedStatus = normalizeStatus(status);
        SkillLifecycleProjectionQuery query = new SkillLifecycleProjectionQuery(
                skillId, version, normalizedStatus, environment, page, pageSize);
        List<SkillLifecycleProjectionResponse> response = service.findSkills(query, actor).stream()
                .map(SkillLifecycleProjectionResponse::from)
                .toList();
        return ResponseEntity.ok(new ApiResponse<>(response, requestId(request)));
    }

    @GetMapping("/skills/{skillId}/impact")
    ResponseEntity<ApiResponse<SkillLifecycleImpactView>> impact(
            @PathVariable String skillId,
            @RequestParam String version,
            HttpServletRequest request) {
        Actor actor = requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.findImpact(skillId, version, actor), requestId(request)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, ADMIN_ROLE);
        return actor;
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_STATUS_CODES.contains(normalized)) {
            throw new SkillLifecycleProjectionQueryInvalidException(
                    "status must be one of " + String.join(", ", SUPPORTED_STATUS_CODES));
        }
        return normalized;
    }
}
