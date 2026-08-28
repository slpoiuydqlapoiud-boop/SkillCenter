package com.huawei.skillcenter.persistence;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/persistence")
public class PersistenceController {
    private final PersistenceControlService service;
    private final ActorResolver actorResolver;

    public PersistenceController(PersistenceControlService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/status")
    ResponseEntity<ApiResponse<PersistenceControlService.PersistenceStatusView>> status(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.status(), requestId(request)));
    }

    @PostMapping("/snapshots")
    ResponseEntity<ApiResponse<PersistenceControlService.PersistenceSnapshotView>> createSnapshot(
            HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.createSnapshot(), requestId(request)));
    }

    @GetMapping("/snapshots")
    ResponseEntity<ApiResponse<List<PersistenceControlService.PersistenceSnapshotView>>> listSnapshots(
            HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.listSnapshots(), requestId(request)));
    }

    @GetMapping("/snapshots/{snapshotId}")
    ResponseEntity<ApiResponse<PersistenceControlService.PersistenceSnapshotView>> getSnapshot(
            @PathVariable String snapshotId, HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.getSnapshot(snapshotId), requestId(request)));
    }

    @PostMapping("/snapshots/{snapshotId}/restore-preflight")
    ResponseEntity<ApiResponse<PersistenceControlService.PersistencePreflightView>> restorePreflight(
            @PathVariable String snapshotId, HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(service.restorePreflight(snapshotId), requestId(request)));
    }

    private void requireAdmin(HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, Set.of("admin"));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
