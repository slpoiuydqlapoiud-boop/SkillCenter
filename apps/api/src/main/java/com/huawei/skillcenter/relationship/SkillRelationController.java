package com.huawei.skillcenter.relationship;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
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

@RestController
@RequestMapping("/api/v1/admin/skill-relations")
public class SkillRelationController {
    private final SkillRelationService service;
    private final ActorResolver actorResolver;

    public SkillRelationController(SkillRelationService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @PostMapping
    ResponseEntity<ApiResponse<SkillRelationView>> create(@RequestBody SkillRelationRequest body,
                                                           HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        SkillRelation relation = service.create(body, actor, requestId(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(SkillRelationView.from(relation), requestId(request)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<SkillRelationView>>> list(
            @RequestParam(required = false) String sourceSkillId,
            @RequestParam(required = false) String sourceVersion,
            @RequestParam(required = false) String targetSkillId,
            @RequestParam(required = false) String targetVersion,
            @RequestParam(required = false) SkillRelationStatus status,
            HttpServletRequest request) {
        SkillRelationQuery query = new SkillRelationQuery(sourceSkillId, sourceVersion, targetSkillId, targetVersion,
                status, SkillRelationQuery.DEFAULT_MAX_DEPTH, SkillRelationQuery.DEFAULT_MAX_NODES);
        List<SkillRelationView> result = service.list(query, actorResolver.resolve(request)).stream()
                .map(SkillRelationView::from).toList();
        return ResponseEntity.ok(new ApiResponse<>(result, requestId(request)));
    }

    @GetMapping("/impact")
    ResponseEntity<ApiResponse<SkillRelationImpact>> impact(@RequestParam String skillId,
                                                            @RequestParam String version,
                                                            @RequestParam(required = false) Integer maxDepth,
                                                            @RequestParam(required = false) Integer maxNodes,
                                                            HttpServletRequest request) {
        SkillRelationQuery query = new SkillRelationQuery(null, null, null, null, null, maxDepth, maxNodes);
        return ResponseEntity.ok(new ApiResponse<>(service.impact(skillId, version, query,
                actorResolver.resolve(request)), requestId(request)));
    }

    @PostMapping("/{relationId}/retire")
    ResponseEntity<ApiResponse<SkillRelationView>> retire(@PathVariable String relationId,
                                                          @RequestBody(required = false) SkillRelationRetireRequest body,
                                                          HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        String reason = body == null ? "" : body.reason();
        SkillRelation retired = service.retire(relationId, reason, actor, requestId(request));
        return ResponseEntity.ok(new ApiResponse<>(SkillRelationView.from(retired), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record SkillRelationView(
            String relationId,
            String sourceSkillId,
            String sourceVersion,
            String targetSkillId,
            String targetVersion,
            SkillRelationType relationType,
            SkillRelationStatus status,
            java.time.Instant declaredAt,
            java.time.Instant retiredAt,
            String statusReason
    ) {
        static SkillRelationView from(SkillRelation relation) {
            return new SkillRelationView(relation.relationId(), relation.sourceSkillId(), relation.sourceVersion(),
                    relation.targetSkillId(), relation.targetVersion(), relation.relationType(), relation.status(),
                    relation.declaredAt(), relation.retiredAt(), relation.statusReason());
        }
    }
}
