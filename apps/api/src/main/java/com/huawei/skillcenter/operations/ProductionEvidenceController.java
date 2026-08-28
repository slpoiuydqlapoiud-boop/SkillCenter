package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/platform/evidence")
public class ProductionEvidenceController {
    private final ProductionEvidenceService service;
    private final ActorResolver actorResolver;

    public ProductionEvidenceController(ProductionEvidenceService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<ProductionEvidence>>> list(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ResponseEntity.ok(new ApiResponse<>(service.list(actor), requestId(request)));
    }

    @PutMapping("/{evidenceId}")
    ResponseEntity<ApiResponse<ProductionEvidence>> update(@PathVariable String evidenceId,
                                                            @RequestBody ProductionEvidenceUpdateRequest body,
                                                            HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        String requestId = requestId(request);
        return ResponseEntity.ok(new ApiResponse<>(service.update(evidenceId, body, actor, requestId), requestId));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
