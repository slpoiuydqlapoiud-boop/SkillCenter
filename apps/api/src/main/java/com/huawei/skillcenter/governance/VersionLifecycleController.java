package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills/{skillId}/versions/{version}")
public class VersionLifecycleController {
    private final VersionLifecycleService service;
    private final ActorResolver actorResolver;

    public VersionLifecycleController(VersionLifecycleService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @PostMapping("/deprecate")
    ResponseEntity<ApiResponse<VersionHistoryController.VersionView>> deprecate(
            @PathVariable String skillId, @PathVariable String version,
            @RequestBody(required = false) VersionLifecycleRequest body, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        SkillVersion updated = service.deprecate(skillId, version, body, actor, requestId(request));
        return ResponseEntity.ok(new ApiResponse<>(VersionHistoryController.VersionView.from(updated), requestId(request)));
    }

    @PostMapping("/withdraw")
    ResponseEntity<ApiResponse<VersionHistoryController.VersionView>> withdraw(
            @PathVariable String skillId, @PathVariable String version,
            @RequestBody(required = false) VersionLifecycleRequest body, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        SkillVersion updated = service.withdraw(skillId, version, body, actor, requestId(request));
        return ResponseEntity.ok(new ApiResponse<>(VersionHistoryController.VersionView.from(updated), requestId(request)));
    }

    @GetMapping("/impact")
    ResponseEntity<ApiResponse<VersionImpact>> impact(@PathVariable String skillId, @PathVariable String version,
                                                      HttpServletRequest request) {
        VersionImpact impact = service.impact(skillId, version, actorResolver.resolve(request));
        return ResponseEntity.ok(new ApiResponse<>(impact, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
