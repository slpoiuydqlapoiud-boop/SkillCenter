package com.huawei.skillcenter.events;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events/installations")
public class InstallationEventController {
    private final InstallationEventService service;

    public InstallationEventController(InstallationEventService service) {
        this.service = service;
    }

    @PostMapping("/batch")
    ResponseEntity<ApiResponse<InstallationEventBatchResponse>> ingestBatch(
            @RequestBody InstallationEventBatchRequest batch, HttpServletRequest request) {
        InstallationEventBatchResponse response = service.ingestBatch(batch);
        int status = response.accepted() > 0 ? 202 : 200;
        return ResponseEntity.status(status).body(new ApiResponse<>(response,
                String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }
}
