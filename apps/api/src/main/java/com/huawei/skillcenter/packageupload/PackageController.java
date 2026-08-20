package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.governance.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/skill-packages")
public class PackageController {
    private final PackageValidationService validationService;
    private final LocalPackageStorage storage;
    private final ReviewService reviewService;
    private final ActorResolver actorResolver;

    public PackageController(PackageValidationService validationService, LocalPackageStorage storage,
                             ReviewService reviewService, ActorResolver actorResolver) {
        this.validationService = validationService;
        this.storage = storage;
        this.reviewService = reviewService;
        this.actorResolver = actorResolver;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<UploadedPackage>> upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) throws Exception {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, java.util.Set.of("maintainer", "admin"));
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".zip")) {
            throw new PackageValidationException(new PackageValidationResult(false, null, null, null, 0,
                    java.util.List.of(new PackageValidationResult.ValidationError("PACKAGE_VALIDATION_FAILED", "file", "必须上传 ZIP 文件"))));
        }
        Path temp = Files.createTempFile("skill-upload-", ".zip");
        try {
            file.transferTo(temp);
            PackageValidationResult result = validationService.validate(temp);
            if (!result.valid()) {
                throw new PackageValidationException(result);
            }
            String packageId = UUID.randomUUID().toString();
            StoredPackage stored = storage.save(temp, packageId);
            reviewService.submitValidatedPackage(result, stored, actor, requestId(request));
            UploadedPackage uploaded = new UploadedPackage(packageId, result.skillId(), result.version(), "pending_review", result.sha256(), result.sizeBytes());
            return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(uploaded, requestId(request)));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record UploadedPackage(String packageId, String skillId, String version, String status, String sha256, long sizeBytes) {
    }
}
