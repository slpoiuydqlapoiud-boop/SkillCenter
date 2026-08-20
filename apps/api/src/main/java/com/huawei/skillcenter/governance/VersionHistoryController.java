package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/skills/{skillId}/versions")
public class VersionHistoryController {
    private final GovernanceStore store;
    private final ActorResolver actorResolver;

    public VersionHistoryController(GovernanceStore store, ActorResolver actorResolver) {
        this.store = store;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<VersionView>>> list(@PathVariable String skillId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        boolean privileged = "reviewer".equals(actor.role()) || "admin".equals(actor.role());
        List<VersionView> versions = store.snapshot().versions().stream()
                .filter(version -> version.skillId().equals(skillId))
                .filter(version -> privileged || "published".equals(version.status()) || "deprecated".equals(version.status()))
                .map(VersionView::from)
                .toList();
        return ResponseEntity.ok(new ApiResponse<>(versions, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record VersionView(String packageId, String skillId, String version, String status,
                              String sha256, long sizeBytes, String uploadedBy, java.time.Instant uploadedAt,
                              String publishedBy, java.time.Instant publishedAt, String reviewId,
                              String statusReason, String replacementVersion, String statusChangedBy,
                              java.time.Instant statusChangedAt) {
        static VersionView from(SkillVersion version) {
            return new VersionView(version.packageId(), version.skillId(), version.version(), version.status(),
                    version.sha256(), version.sizeBytes(), version.uploadedBy(), version.uploadedAt(),
                    version.publishedBy(), version.publishedAt(), version.reviewId(), version.statusReason(),
                    version.replacementVersion(), version.statusChangedBy(), version.statusChangedAt());
        }
    }
}
