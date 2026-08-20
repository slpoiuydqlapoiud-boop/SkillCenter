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
@RequestMapping("/api/v1/events/invocations")
public class InvocationEventController {
    private final InvocationEventService service;

    public InvocationEventController(InvocationEventService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<IngestionResponse>> ingest(@RequestBody InvocationEvent event,
                                                           HttpServletRequest request) {
        InvocationEventService.IngestResult result = service.ingest(event);
        IngestionResponse body = new IngestionResponse(result.eventId(), true, result.duplicate());
        return ResponseEntity.status(result.duplicate() ? 200 : 202)
                .body(new ApiResponse<>(body, String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }

    @PostMapping("/batch")
    ResponseEntity<ApiResponse<InvocationEventBatchResponse>> ingestBatch(@RequestBody InvocationEventBatchRequest batch,
                                                                           HttpServletRequest request) {
        InvocationEventBatchResponse response = service.ingestBatch(batch);
        int status = response.accepted() > 0 ? 202 : 200;
        return ResponseEntity.status(status).body(new ApiResponse<>(response,
                String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }

    public record IngestionResponse(java.util.UUID eventId, boolean accepted, boolean duplicate) {}
}
