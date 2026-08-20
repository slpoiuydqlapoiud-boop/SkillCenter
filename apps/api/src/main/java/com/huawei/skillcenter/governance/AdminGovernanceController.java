package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminGovernanceController {
    private final GovernanceConfigurationService service;
    private final ActorResolver actorResolver;

    public AdminGovernanceController(GovernanceConfigurationService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/teams")
    ResponseEntity<ApiResponse<List<TeamDefinition>>> teams(@RequestParam(required = false) String status,
                                                              HttpServletRequest request) {
        GovernanceConfigurationView view = adminView(request);
        return ok(filterStatus(view.teams(), status), request);
    }

    @PostMapping("/teams")
    ResponseEntity<ApiResponse<TeamDefinition>> createTeam(@RequestBody TeamMutation body, HttpServletRequest request) {
        Actor actor = admin(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(okBody(service.createTeam(body, actor, requestId(request)), request));
    }

    @PutMapping("/teams/{teamId}")
    ResponseEntity<ApiResponse<TeamDefinition>> updateTeam(@PathVariable String teamId, @RequestBody TeamMutation body,
                                                            HttpServletRequest request) {
        TeamMutation value = body == null ? null : new TeamMutation(teamId, body.name(), body.description(),
                body.ownerUserId(), body.memberUserIds());
        return ok(service.upsertTeam(value, admin(request), requestId(request)), request);
    }

    @DeleteMapping("/teams/{teamId}")
    ResponseEntity<ApiResponse<Void>> deactivateTeam(@PathVariable String teamId, HttpServletRequest request) {
        service.deactivateTeam(teamId, admin(request), requestId(request));
        return ok(null, request);
    }

    @GetMapping("/role-bindings")
    ResponseEntity<ApiResponse<List<RoleBinding>>> roleBindings(@RequestParam(required = false) String userId,
                                                                  @RequestParam(required = false) String teamId,
                                                                  @RequestParam(required = false) String status,
                                                                  HttpServletRequest request) {
        List<RoleBinding> values = adminView(request).roleBindings().stream()
                .filter(item -> blank(userId) || item.userId().equals(userId))
                .filter(item -> blank(teamId) || java.util.Objects.equals(item.teamId(), teamId))
                .filter(item -> blank(status) || item.status().equalsIgnoreCase(status)).toList();
        return ok(values, request);
    }

    @PutMapping("/role-bindings/{userId}")
    ResponseEntity<ApiResponse<RoleBinding>> saveRoleBinding(@PathVariable String userId,
                                                               @RequestBody RoleBindingMutation body,
                                                               HttpServletRequest request) {
        return ok(service.upsertRoleBinding(userId, body, admin(request), requestId(request)), request);
    }

    @DeleteMapping("/role-bindings/{userId}")
    ResponseEntity<ApiResponse<Void>> deactivateRoleBinding(@PathVariable String userId, HttpServletRequest request) {
        service.deactivateRoleBinding(userId, admin(request), requestId(request));
        return ok(null, request);
    }

    @GetMapping({"/taxonomy/categories", "/taxonomy/categories/"})
    ResponseEntity<ApiResponse<List<CategoryDefinition>>> categories(@RequestParam(required = false) String status,
                                                                       HttpServletRequest request) {
        return ok(filterStatus(adminView(request).categories(), status), request);
    }

    @PostMapping("/taxonomy/categories")
    ResponseEntity<ApiResponse<CategoryDefinition>> createCategory(@RequestBody TaxonomyMutation body,
                                                                     HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(okBody(
                service.createCategory(body, admin(request), requestId(request)), request));
    }

    @PutMapping("/taxonomy/categories/{code}")
    ResponseEntity<ApiResponse<CategoryDefinition>> updateCategory(@PathVariable String code,
                                                                     @RequestBody TaxonomyMutation body,
                                                                     HttpServletRequest request) {
        TaxonomyMutation value = body == null ? null : new TaxonomyMutation(code, body.displayName(), body.description(),
                body.sortOrder());
        return ok(service.upsertCategory(value, admin(request), requestId(request)), request);
    }

    @DeleteMapping("/taxonomy/categories/{code}")
    ResponseEntity<ApiResponse<Void>> deactivateCategory(@PathVariable String code, HttpServletRequest request) {
        service.deactivateCategory(code, admin(request), requestId(request));
        return ok(null, request);
    }

    @GetMapping("/taxonomy/tags")
    ResponseEntity<ApiResponse<List<TagDefinition>>> tags(@RequestParam(required = false) String status,
                                                           HttpServletRequest request) {
        return ok(filterStatus(adminView(request).tags(), status), request);
    }

    @PostMapping("/taxonomy/tags")
    ResponseEntity<ApiResponse<TagDefinition>> createTag(@RequestBody TaxonomyMutation body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(okBody(service.createTag(body, admin(request), requestId(request)), request));
    }

    @PutMapping("/taxonomy/tags/{code}")
    ResponseEntity<ApiResponse<TagDefinition>> updateTag(@PathVariable String code, @RequestBody TaxonomyMutation body,
                                                          HttpServletRequest request) {
        TaxonomyMutation value = body == null ? null : new TaxonomyMutation(code, body.displayName(), body.description(),
                body.sortOrder());
        return ok(service.upsertTag(value, admin(request), requestId(request)), request);
    }

    @DeleteMapping("/taxonomy/tags/{code}")
    ResponseEntity<ApiResponse<Void>> deactivateTag(@PathVariable String code, HttpServletRequest request) {
        service.deactivateTag(code, admin(request), requestId(request));
        return ok(null, request);
    }

    @GetMapping("/collections")
    ResponseEntity<ApiResponse<List<CollectionDefinition>>> collections(@RequestParam(required = false) String status,
                                                                          HttpServletRequest request) {
        return ok(filterStatus(adminView(request).collections(), status), request);
    }

    @PostMapping("/collections")
    ResponseEntity<ApiResponse<CollectionDefinition>> createCollection(@RequestBody CollectionMutation body,
                                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(okBody(
                service.createCollection(body, admin(request), requestId(request)), request));
    }

    @GetMapping("/collections/{collectionId}")
    ResponseEntity<ApiResponse<CollectionDefinition>> collection(@PathVariable String collectionId,
                                                                   HttpServletRequest request) {
        return ok(findCollection(collectionId, adminView(request).collections()), request);
    }

    @PutMapping("/collections/{collectionId}")
    ResponseEntity<ApiResponse<CollectionDefinition>> updateCollection(@PathVariable String collectionId,
                                                                         @RequestBody CollectionMutation body,
                                                                         HttpServletRequest request) {
        CollectionMutation value = body == null ? null : new CollectionMutation(collectionId, body.name(), body.description(),
                body.ownerTeamId(), body.visibility(), body.skillIds(), body.sortOrder());
        return ok(service.upsertCollection(value, admin(request), requestId(request)), request);
    }

    @DeleteMapping("/collections/{collectionId}")
    ResponseEntity<ApiResponse<Void>> deactivateCollection(@PathVariable String collectionId, HttpServletRequest request) {
        service.deactivateCollection(collectionId, admin(request), requestId(request));
        return ok(null, request);
    }

    @PutMapping("/collections/{collectionId}/skills/{skillId}")
    ResponseEntity<ApiResponse<CollectionDefinition>> addSkill(@PathVariable String collectionId,
                                                                @PathVariable String skillId,
                                                                HttpServletRequest request) {
        return ok(service.addSkill(collectionId, skillId, admin(request), requestId(request)), request);
    }

    @DeleteMapping("/collections/{collectionId}/skills/{skillId}")
    ResponseEntity<ApiResponse<CollectionDefinition>> removeSkill(@PathVariable String collectionId,
                                                                    @PathVariable String skillId,
                                                                    HttpServletRequest request) {
        return ok(service.removeSkill(collectionId, skillId, admin(request), requestId(request)), request);
    }

    @GetMapping("/policies")
    ResponseEntity<ApiResponse<PlatformPolicy>> policy(HttpServletRequest request) {
        return ok(adminView(request).platformPolicy(), request);
    }

    @PutMapping("/policies")
    ResponseEntity<ApiResponse<PlatformPolicy>> updatePolicy(@RequestBody PolicyMutation body, HttpServletRequest request) {
        return ok(service.updatePolicy(body, admin(request), requestId(request)), request);
    }

    private Actor admin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("admin"));
        return actor;
    }

    private GovernanceConfigurationView adminView(HttpServletRequest request) {
        return service.readAdmin(admin(request));
    }

    private <T> T findCollection(String collectionId, List<CollectionDefinition> values) {
        return (T) values.stream().filter(item -> item.collectionId().equals(collectionId)).findFirst()
                .orElseThrow(() -> new CollectionNotFoundException(collectionId));
    }

    private <T> List<T> filterStatus(List<T> values, String status) {
        if (blank(status)) return values;
        return values.stream().filter(value -> {
            if (value instanceof TeamDefinition team) return team.status().equalsIgnoreCase(status);
            if (value instanceof RoleBinding binding) return binding.status().equalsIgnoreCase(status);
            if (value instanceof CategoryDefinition category) return category.status().equalsIgnoreCase(status);
            if (value instanceof TagDefinition tag) return tag.status().equalsIgnoreCase(status);
            if (value instanceof CollectionDefinition collection) return collection.status().equalsIgnoreCase(status);
            return false;
        }).toList();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok(okBody(data, request));
    }

    private <T> ApiResponse<T> okBody(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
