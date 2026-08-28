package com.huawei.skillcenter.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/search/index")
public class SkillSearchIndexController {
    private final SkillSearchRefreshCoordinator coordinator;
    private final ActorResolver actorResolver;

    public SkillSearchIndexController(SkillSearchRefreshCoordinator coordinator, ActorResolver actorResolver) {
        this.coordinator = coordinator;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/status")
    ResponseEntity<ApiResponse<SkillSearchIndexAdminView>> status(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(SkillSearchIndexAdminView.from(coordinator.status()), requestId(request)));
    }

    @PostMapping("/rebuild")
    ResponseEntity<ApiResponse<SkillSearchIndexAdminView>> rebuild(@RequestBody RebuildRequest body,
                                                                     HttpServletRequest request) {
        Actor actor = requireAdmin(request);
        String requestId = requiredRequestId(request);
        String expectedSourceHash = body == null ? "" : body.expectedSourceHash();
        if (expectedSourceHash != null && expectedSourceHash.length() > 256) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_INVALID_REQUEST");
        }
        SkillSearchRebuildResult result = coordinator.rebuild(expectedSourceHash, actor.userId(), requestId);
        if (!result.reasonCode().isEmpty()) {
            throw new SkillSearchIndexControlException(result.reasonCode());
        }
        return ResponseEntity.ok(new ApiResponse<>(SkillSearchIndexAdminView.from(coordinator.status()), requestId));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("admin"));
        return actor;
    }

    private String requiredRequestId(HttpServletRequest request) {
        String supplied = request.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        if (supplied == null || supplied.isBlank() || supplied.length() > 128) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_REQUEST_ID_REQUIRED");
        }
        return requestId(request);
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record RebuildRequest(String expectedSourceHash) {
    }
}
