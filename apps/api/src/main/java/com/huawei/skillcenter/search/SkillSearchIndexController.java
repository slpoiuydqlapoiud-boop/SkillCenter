package com.huawei.skillcenter.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SkillSearchConnectivityProbeService connectivityProbe;

    public SkillSearchIndexController(SkillSearchRefreshCoordinator coordinator, ActorResolver actorResolver) {
        this(coordinator, actorResolver, null);
    }

    @Autowired
    public SkillSearchIndexController(SkillSearchRefreshCoordinator coordinator, ActorResolver actorResolver,
                                      SkillSearchConnectivityProbeService connectivityProbe) {
        this.coordinator = coordinator;
        this.actorResolver = actorResolver;
        this.connectivityProbe = connectivityProbe;
    }

    @GetMapping("/status")
    ResponseEntity<ApiResponse<SkillSearchIndexAdminView>> status(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(SkillSearchIndexAdminView.from(coordinator.index(), coordinator.status()), requestId(request)));
    }

    @PostMapping("/rebuild")
    ResponseEntity<ApiResponse<SkillSearchIndexAdminView>> rebuild(@RequestBody JsonNode body,
                                                                     HttpServletRequest request) {
        Actor actor = requireAdmin(request);
        String requestId = requiredRequestId(request);
        String expectedSourceHash = parseExpectedSourceHash(body);
        if (expectedSourceHash != null && expectedSourceHash.length() > 256) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_INVALID_REQUEST");
        }
        SkillSearchRebuildResult result = coordinator.rebuild(expectedSourceHash, actor.userId(), requestId);
        if (!result.reasonCode().isEmpty()) {
            throw new SkillSearchIndexControlException(result.reasonCode());
        }
        return ResponseEntity.ok(new ApiResponse<>(SkillSearchIndexAdminView.from(coordinator.index(), coordinator.status()), requestId));
    }

    @PostMapping("/probe")
    ResponseEntity<ApiResponse<SkillSearchProbeResult>> probe(HttpServletRequest request) {
        Actor actor = requireAdmin(request);
        if (connectivityProbe == null) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_PROBE_UNAVAILABLE");
        }
        String requestId = requestId(request);
        return ResponseEntity.ok(new ApiResponse<>(connectivityProbe.probe(actor, requestId), requestId));
    }

    private String parseExpectedSourceHash(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new SkillSearchIndexControlException("EVENT_SCHEMA_INVALID");
        }
        var fields = body.fieldNames();
        while (fields.hasNext()) {
            if (!"expectedSourceHash".equals(fields.next())) {
                throw new SkillSearchIndexControlException("EVENT_SCHEMA_INVALID");
            }
        }
        JsonNode value = body.get("expectedSourceHash");
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw new SkillSearchIndexControlException("EVENT_SCHEMA_INVALID");
        }
        return value.textValue();
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

}
