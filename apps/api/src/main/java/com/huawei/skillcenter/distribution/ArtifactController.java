package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/distribution/artifacts")
public class ArtifactController {
    private final ArtifactDownloadService service;

    public ArtifactController(ArtifactDownloadService service) {
        this.service = service;
    }

    @GetMapping("/{skillId}/{version}")
    ResponseEntity<Resource> download(@PathVariable String skillId,
                                      @PathVariable String version,
                                      @RequestParam String token,
                                      HttpServletRequest request) {
        ArtifactDownloadService.DownloadedArtifact artifact = service.download(skillId, version, token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(artifact.sizeBytes())
                .header("X-Skill-SHA256", artifact.sha256())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + skillId + "-" + version + ".zip\"")
                .header("X-Request-Id", String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)))
                .body(artifact.resource());
    }
}
