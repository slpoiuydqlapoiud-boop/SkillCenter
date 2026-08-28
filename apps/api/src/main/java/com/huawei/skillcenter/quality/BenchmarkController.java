package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.ForbiddenException;
import com.huawei.skillcenter.operations.InvalidOperationsQueryException;
import com.huawei.skillcenter.operations.RuntimeOperationsWindow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/quality/benchmarks")
public class BenchmarkController {
    private final BenchmarkService service;
    private final ActorResolver actorResolver;

    public BenchmarkController(BenchmarkService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @PostMapping
    ResponseEntity<ApiResponse<BenchmarkResult>> create(@RequestBody BenchmarkRequest request,
                                                        HttpServletRequest httpRequest) {
        Actor actor = requireAdmin(httpRequest);
        RuntimeOperationsWindow window = parseWindow(request == null ? null : request.window());
        BenchmarkResult result = service.run(request, window, actor, requestId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(result, requestId(httpRequest)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<BenchmarkResult>>> list(@RequestParam(required = false) String skillId,
                                                            @RequestParam(required = false) String dataSource,
                                                            @RequestParam(required = false) String runtimeId,
                                                            @RequestParam(required = false) String mcpServerId,
                                                            @RequestParam(required = false) String llmProviderId,
                                                            @RequestParam(required = false) String suiteId,
                                                            @RequestParam(required = false) String suiteVersion,
                                                            HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(service.list(skillId, dataSource, runtimeId, mcpServerId,
                llmProviderId, suiteId, suiteVersion), requestId(httpRequest)));
    }

    private Actor requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        if (!"admin".equals(actor.role())) throw new ForbiddenException("Only admin can manage quality benchmarks");
        return actor;
    }

    private RuntimeOperationsWindow parseWindow(String value) {
        try {
            return RuntimeOperationsWindow.parse(value == null || value.isBlank() ? "24h" : value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidOperationsQueryException(exception.getMessage());
        }
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
