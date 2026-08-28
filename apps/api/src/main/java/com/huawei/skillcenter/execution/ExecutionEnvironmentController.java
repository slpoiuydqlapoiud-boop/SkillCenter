package com.huawei.skillcenter.execution;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/execution-environments")
public class ExecutionEnvironmentController {
    private final ActorResolver actorResolver;
    private final ExecutionEnvironmentService service;

    public ExecutionEnvironmentController(ActorResolver actorResolver, ExecutionEnvironmentService service) {
        this.actorResolver = actorResolver;
        this.service = service;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<ExecutionEnvironment>>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.list(kind, status, actor), requestId(request)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<ExecutionEnvironment>> create(@RequestBody ExecutionEnvironmentCreateRequest body,
                                                              HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(new ApiResponse<>(service.create(body, actor, requestId(request)), requestId(request)));
    }

    @PatchMapping("/{kind}/{environmentId}/status")
    ResponseEntity<ApiResponse<ExecutionEnvironment>> changeStatus(
            @PathVariable String kind,
            @PathVariable String environmentId,
            @RequestBody ExecutionEnvironmentStatusRequest body,
            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.changeStatus(kind, environmentId, body, actor, requestId(request)), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
