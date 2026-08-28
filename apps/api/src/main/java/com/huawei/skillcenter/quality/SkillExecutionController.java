package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.ForbiddenException;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/runner/executions")
public class SkillExecutionController {
    private final SkillExecutionService service;
    private final ActorResolver actorResolver;

    public SkillExecutionController(SkillExecutionService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @PostMapping
    ResponseEntity<ApiResponse<SkillExecutionRecord>> execute(@RequestBody SkillExecutionRequest request,
                                                                HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        SkillExecutionRecord record = service.execute(request, actor, requestId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(record, requestId(httpRequest)));
    }

    @GetMapping("/{executionId}")
    ResponseEntity<ApiResponse<SkillExecutionRecord>> find(@PathVariable UUID executionId,
                                                             HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.find(executionId), requestId(httpRequest)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<SkillExecutionRecord>>> list(
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String runtimeId,
            @RequestParam(required = false) String mcpServerId,
            @RequestParam(required = false) String llmProviderId,
            HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.list(skillId, dataSource, runtimeId, mcpServerId, llmProviderId), requestId(httpRequest)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) {
            throw new ForbiddenException("Only admin can execute or inspect runner records");
        }
        return actor;
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
