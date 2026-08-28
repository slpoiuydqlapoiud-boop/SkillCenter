package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events/runtime-summaries")
public class RuntimeSummaryController {
    private final RuntimeSummaryService service;

    public RuntimeSummaryController(RuntimeSummaryService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<IngestionResponse>> ingest(@RequestBody RuntimeSummary summary,
                                                          HttpServletRequest request) {
        RuntimeSummaryService.IngestResult result = service.ingest(summary);
        return ResponseEntity.status(result.duplicate() ? 200 : 202)
                .body(new ApiResponse<>(new IngestionResponse(result.eventId(), true, result.duplicate()),
                        String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }

    @PostMapping("/batch")
    ResponseEntity<ApiResponse<RuntimeSummaryBatchResponse>> ingestBatch(@RequestBody RuntimeSummaryBatchRequest batch,
                                                                          HttpServletRequest request) {
        RuntimeSummaryBatchResponse response = service.ingestBatch(batch);
        return ResponseEntity.status(response.accepted() > 0 ? 202 : 200)
                .body(new ApiResponse<>(response,
                        String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }

    public record IngestionResponse(java.util.UUID eventId, boolean accepted, boolean duplicate) {
    }
}
