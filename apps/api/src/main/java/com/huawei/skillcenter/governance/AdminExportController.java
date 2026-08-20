package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.security.IdempotencyFingerprint;
import com.huawei.skillcenter.security.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/exports")
public class AdminExportController {
    private final ExportService exportService;
    private final ActorResolver actorResolver;
    private final IdempotencyService idempotencyService;

    public AdminExportController(ExportService exportService, ActorResolver actorResolver,
                                 IdempotencyService idempotencyService) {
        this.exportService = exportService;
        this.actorResolver = actorResolver;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<ExportJob>>> list(@RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String dataset,
                                                      HttpServletRequest servletRequest) {
        List<ExportJob> jobs = exportService.list(actorResolver.resolve(servletRequest), status, dataset);
        return ResponseEntity.ok(new ApiResponse<>(jobs, requestId(servletRequest)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<ExportJob>> create(@RequestBody ExportRequest request, HttpServletRequest servletRequest) {
        Actor actor = actorResolver.resolve(servletRequest);
        String key = servletRequest.getHeader("Idempotency-Key");
        String fingerprint = IdempotencyFingerprint.sha256(String.valueOf(request));
        idempotencyService.claim("EXPORT_CREATE", actor.userId(), key, fingerprint);
        try {
            ExportJob job = exportService.create(request, actor, requestId(servletRequest));
            return ResponseEntity.accepted().body(new ApiResponse<>(job, requestId(servletRequest)));
        } catch (RuntimeException exception) {
            idempotencyService.release("EXPORT_CREATE", actor.userId(), key, fingerprint);
            throw exception;
        }
    }

    @GetMapping("/{jobId}")
    ResponseEntity<ApiResponse<ExportJob>> detail(@PathVariable UUID jobId, HttpServletRequest servletRequest) {
        ExportJob job = exportService.detail(jobId, actorResolver.resolve(servletRequest));
        return ResponseEntity.ok(new ApiResponse<>(job, requestId(servletRequest)));
    }

    @PostMapping("/{jobId}/download-url")
    ResponseEntity<ApiResponse<DownloadUrlResponse>> downloadUrl(@PathVariable UUID jobId,
                                                                  HttpServletRequest servletRequest) {
        DownloadUrlResponse response = exportService.issueDownloadUrl(jobId, actorResolver.resolve(servletRequest),
                requestId(servletRequest));
        return ResponseEntity.ok(new ApiResponse<>(response, requestId(servletRequest)));
    }

    @PostMapping("/{jobId}/retry")
    ResponseEntity<ApiResponse<ExportJob>> retry(@PathVariable UUID jobId, HttpServletRequest servletRequest) {
        ExportJob job = exportService.retry(jobId, actorResolver.resolve(servletRequest), requestId(servletRequest));
        return ResponseEntity.accepted().body(new ApiResponse<>(job, requestId(servletRequest)));
    }

    @GetMapping("/{jobId}/download")
    ResponseEntity<byte[]> download(@PathVariable UUID jobId, @RequestParam String token,
                                    HttpServletRequest servletRequest) {
        ExportDownload download = exportService.download(jobId, token, actorResolver.resolve(servletRequest),
                requestId(servletRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .body(download.content());
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
