package com.huawei.skillcenter.skill;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {
    private final SkillCatalogService service;
    private final ActorResolver actorResolver;

    public SkillController(SkillCatalogService service) {
        this(service, new ActorResolver());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillController(SkillCatalogService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<SkillPageResponse>> list(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String risk,
            @RequestParam(defaultValue = "updated") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize,
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        PageResult<SkillSummary> result = service.list(new SkillQuery(query, category, status, risk, page, pageSize, sort), actor);
        SkillPageResponse response = new SkillPageResponse(result.items(), result.page(), result.pageSize(), result.total());
        return ResponseEntity.ok(new ApiResponse<>(response, requestId(request)));
    }

    @GetMapping("/{skillId}")
    ResponseEntity<ApiResponse<SkillRecord>> detail(@PathVariable String skillId, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(service.detail(skillId, actorResolver.resolve(request)), requestId(request)));
    }

    @GetMapping("/{skillId}/content")
    ResponseEntity<ApiResponse<String>> content(@PathVariable String skillId, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(service.content(skillId, actorResolver.resolve(request)), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record SkillPageResponse(java.util.List<SkillSummary> items, int page, int pageSize, long total) {
    }
}
