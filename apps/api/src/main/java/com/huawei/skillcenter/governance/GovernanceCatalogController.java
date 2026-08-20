package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.skill.CollectionService;
import com.huawei.skillcenter.skill.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class GovernanceCatalogController {
    private final GovernanceConfigurationService service;
    private final CollectionService collectionService;
    private final ActorResolver actorResolver;

    public GovernanceCatalogController(GovernanceConfigurationService service, CollectionService collectionService,
                                       ActorResolver actorResolver) {
        this.service = service;
        this.collectionService = collectionService;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/api/v1/governance/taxonomy")
    ResponseEntity<ApiResponse<GovernanceTaxonomyView>> taxonomy(HttpServletRequest request) {
        GovernanceConfigurationView view = service.read(actorResolver.resolve(request));
        return ok(new GovernanceTaxonomyView(view.categories(), view.tags(), view.collections(), view.platformPolicy()), request);
    }

    @GetMapping("/api/v1/collections")
    ResponseEntity<ApiResponse<CollectionPage>> collections(@RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "12") int pageSize,
                                                              @RequestParam(defaultValue = "") String query,
                                                              @RequestParam(defaultValue = "updated") String sort,
                                                              HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        PageResult<CollectionDefinition> result = collectionService.page(actor, page, pageSize, query, sort);
        return ok(new CollectionPage(result.items(), result.page(), result.pageSize(), result.total()), request);
    }

    @GetMapping("/api/v1/collections/{collectionId}")
    ResponseEntity<ApiResponse<CollectionService.CollectionDetail>> collection(@PathVariable String collectionId,
                                                                                 HttpServletRequest request) {
        return ok(collectionService.detail(collectionId, actorResolver.resolve(request)), request);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(data, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record GovernanceTaxonomyView(List<CategoryDefinition> categories, List<TagDefinition> tags,
                                         List<CollectionDefinition> collections, PlatformPolicy platformPolicy) {
    }

    public record CollectionPage(List<CollectionDefinition> items, int page, int pageSize, long total) {
    }
}
