package com.huawei.skillcenter.skill;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
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

    public SkillController(SkillCatalogService service) {
        this.service = service;
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
        PageResult<SkillSummary> result = service.list(new SkillQuery(query, category, status, risk, page, pageSize, sort));
        SkillPageResponse response = new SkillPageResponse(result.items(), result.page(), result.pageSize(), result.total());
        return ResponseEntity.ok(new ApiResponse<>(response, requestId(request)));
    }

    @GetMapping("/{skillId}")
    ResponseEntity<ApiResponse<SkillRecord>> detail(@PathVariable String skillId, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(service.detail(skillId), requestId(request)));
    }

    @GetMapping("/{skillId}/content")
    ResponseEntity<ApiResponse<String>> content(@PathVariable String skillId, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(service.content(skillId), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record SkillPageResponse(java.util.List<SkillSummary> items, int page, int pageSize, long total) {
    }
}
