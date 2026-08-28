package com.huawei.skillcenter.access;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/skill-access/scopes")
public class SkillScopeController {
    private final SkillAuthorizationService service;
    private final ActorResolver actorResolver;

    public SkillScopeController(SkillAuthorizationService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<SkillScopeView>> get(@RequestParam String skillId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        service.requireScopeReadable(skillId, actor);
        boolean explicit = service.hasDeclaredScope(skillId);
        SkillScope scope = service.effectiveScope(skillId);
        return ResponseEntity.ok(new ApiResponse<>(SkillScopeView.from(scope, explicit), requestId(request)));
    }

    @PutMapping("/{skillId}")
    ResponseEntity<ApiResponse<SkillScopeView>> put(@PathVariable String skillId,
                                                    @RequestBody SkillScopeUpdateRequest body,
                                                    HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        boolean existed = service.hasDeclaredScope(skillId);
        SkillScope updated = service.updateScope(skillId,
                new SkillScopeMutation(body.visibility(), body.ownerTeamId(), body.maintainerUserIds(),
                        body.revision(), actor.userId(), actor.userId()),
                actor, requestId(request));
        HttpStatus status = existed ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(new ApiResponse<>(SkillScopeView.from(updated, true), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    record SkillScopeUpdateRequest(
            SkillVisibility visibility,
            String ownerTeamId,
            List<String> maintainerUserIds,
            int revision
    ) {
    }

    record SkillScopeView(
            String skillId,
            SkillVisibility visibility,
            String ownerTeamId,
            List<String> maintainerUserIds,
            int revision,
            String declaredAt,
            String updatedAt
    ) {
        static SkillScopeView from(SkillScope scope, boolean explicit) {
            if (!explicit) {
                return new SkillScopeView(scope.skillId(), SkillVisibility.PUBLIC, "", List.of(), 0, null, null);
            }
            return new SkillScopeView(scope.skillId(), scope.visibility(), scope.ownerTeamId(),
                    scope.maintainerUserIds(), scope.revision(),
                    scope.declaredAt() == null ? null : scope.declaredAt().toString(),
                    scope.updatedAt() == null ? null : scope.updatedAt().toString());
        }
    }
}
