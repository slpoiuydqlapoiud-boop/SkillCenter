package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/retention")
public class AdminRetentionController {
    private final RetentionService retentionService;
    private final ActorResolver actorResolver;

    public AdminRetentionController(RetentionService retentionService, ActorResolver actorResolver) {
        this.retentionService = retentionService;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<RetentionPolicy>> get(HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(retentionService.get(actorResolver.resolve(request)), requestId(request)));
    }

    @PutMapping
    ResponseEntity<ApiResponse<RetentionPolicy>> update(@RequestBody RetentionPolicyMutation mutation,
                                                         HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(retentionService.update(mutation, actor, requestId(request)),
                requestId(request)));
    }

    @PostMapping("/preview")
    ResponseEntity<ApiResponse<RetentionPreview>> preview(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(retentionService.preview(actor, requestId(request)), requestId(request)));
    }

    @PostMapping("/execute")
    ResponseEntity<ApiResponse<RetentionExecutionResult>> execute(@RequestBody RetentionExecutionRequest body,
                                                                   HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(retentionService.execute(body, actor, requestId(request)), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
