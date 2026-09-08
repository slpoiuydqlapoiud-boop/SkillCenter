package com.huawei.skillcenter.search;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Admin-only lifecycle controls for shared search refresh consumers. */
@RestController
@RequestMapping("/api/v1/admin/search/consumers")
@Conditional(SkillSearchRefreshEventCondition.PostgresqlPersistence.class)
@ConditionalOnProperty(name = "skill-center.search-index-events.enabled", havingValue = "true")
public class SkillSearchRefreshConsumerController {
    private final SkillSearchRefreshEventStore store;
    private final ActorResolver actorResolver;

    public SkillSearchRefreshConsumerController(SkillSearchRefreshEventStore store, ActorResolver actorResolver) {
        if (store == null || actorResolver == null) throw new IllegalArgumentException("store and actorResolver are required");
        this.store = store;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<SkillSearchRefreshConsumerState>>> list(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(store.listConsumers(), requestId(request)));
    }

    @PostMapping("/{consumerId}/activate")
    ResponseEntity<ApiResponse<SkillSearchRefreshConsumerState>> activate(@PathVariable String consumerId,
                                                                            HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(store.activateConsumer(
                boundedConsumerId(consumerId), Instant.now()), requestId(request)));
    }

    @PostMapping("/{consumerId}/retire")
    ResponseEntity<ApiResponse<SkillSearchRefreshConsumerState>> retire(@PathVariable String consumerId,
                                                                          HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(store.retireConsumer(
                boundedConsumerId(consumerId), Instant.now()), requestId(request)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("admin"));
        return actor;
    }

    private String boundedConsumerId(String consumerId) {
        return SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }
}
