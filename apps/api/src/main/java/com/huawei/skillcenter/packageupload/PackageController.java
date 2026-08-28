package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.distribution.ArtifactStorage;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.RoleGuard;
import com.huawei.skillcenter.governance.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/skill-packages")
public class PackageController {
    private final PackageValidationService validationService;
    private final ArtifactStorage storage;
    private final ReviewService reviewService;
    private final ActorResolver actorResolver;
    private final SkillAuthorizationService authorizationService;
    private final ResumablePackageUploadService resumableUploadService;
    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$");

    public PackageController(PackageValidationService validationService, LocalPackageStorage storage,
                             ReviewService reviewService, ActorResolver actorResolver) {
        this(validationService, storage, reviewService, actorResolver, null, null);
    }

    public PackageController(PackageValidationService validationService, ArtifactStorage storage,
                             ReviewService reviewService, ActorResolver actorResolver,
                             SkillAuthorizationService authorizationService) {
        this(validationService, storage, reviewService, actorResolver, authorizationService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PackageController(PackageValidationService validationService, ArtifactStorage storage,
                             ReviewService reviewService, ActorResolver actorResolver,
                             SkillAuthorizationService authorizationService,
                             ResumablePackageUploadService resumableUploadService) {
        this.validationService = validationService;
        this.storage = storage;
        this.reviewService = reviewService;
        this.actorResolver = actorResolver;
        this.authorizationService = authorizationService;
        this.resumableUploadService = resumableUploadService;
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
            if (authorizationService != null) {
                authorizationService.requireSubmitVersion(result.skillId(), actor);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(
                    submit(temp, actor, requestId(request), result), requestId(request)));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @PostMapping(path = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<ResumablePackageUploadService.UploadProgress>> startUpload(
            @RequestBody CreateUploadRequest body, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, java.util.Set.of("maintainer", "admin"));
        ResumablePackageUploadService.UploadProgress progress = resumableUploadService.create(
                body == null ? null : body.fileName(), body == null ? 0 : body.totalBytes(), actor.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(progress, requestId(request)));
    }

    @GetMapping("/uploads/{uploadId}")
    ResponseEntity<ApiResponse<ResumablePackageUploadService.UploadProgress>> uploadProgress(
            @PathVariable String uploadId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, java.util.Set.of("maintainer", "admin"));
        resumableUploadService.requireOwner(uploadId, actor.userId());
        return ResponseEntity.ok(new ApiResponse<>(resumableUploadService.progress(uploadId), requestId(request)));
    }

    @PutMapping(path = "/uploads/{uploadId}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<ApiResponse<ResumablePackageUploadService.UploadProgress>> appendUpload(
            @PathVariable String uploadId, @RequestHeader("Content-Range") String contentRange,
            HttpServletRequest request) throws Exception {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, java.util.Set.of("maintainer", "admin"));
        resumableUploadService.requireOwner(uploadId, actor.userId());
        long[] range = parseRange(contentRange);
        long expectedLength = range[1] - range[0] + 1;
        if (expectedLength >= Integer.MAX_VALUE) {
            throw new ResumablePackageUploadException("UPLOAD_CHUNK_TOO_LARGE", "上传分片超过平台限制",
                    HttpStatus.BAD_REQUEST);
        }
        byte[] chunk = readAtMost(request.getInputStream(), (int) expectedLength + 1);
        ResumablePackageUploadService.UploadProgress progress = resumableUploadService.append(
                uploadId, range[0], range[1], range[2], chunk);
        return ResponseEntity.ok(new ApiResponse<>(progress, requestId(request)));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    ResponseEntity<ApiResponse<UploadedPackage>> completeUpload(@PathVariable String uploadId,
                                                                 HttpServletRequest request) throws Exception {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, java.util.Set.of("maintainer", "admin"));
        resumableUploadService.requireOwner(uploadId, actor.userId());
        ResumablePackageUploadService.CompletedUpload completed = resumableUploadService.complete(uploadId);
        try {
            PackageValidationResult result = validationService.validate(completed.path());
            if (!result.valid()) {
                throw new PackageValidationException(result);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(
                    submit(completed.path(), actor, requestId(request), result), requestId(request)));
        } finally {
            resumableUploadService.discard(uploadId);
        }
    }

    @DeleteMapping("/uploads/{uploadId}")
    ResponseEntity<Void> cancelUpload(@PathVariable String uploadId, HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        RoleGuard.require(actor, java.util.Set.of("maintainer", "admin"));
        resumableUploadService.requireOwner(uploadId, actor.userId());
        resumableUploadService.discard(uploadId);
        return ResponseEntity.noContent().build();
    }

    private UploadedPackage submit(Path path, Actor actor, String requestId, PackageValidationResult result) throws Exception {
        if (authorizationService != null) {
            authorizationService.requireSubmitVersion(result.skillId(), actor);
        }
        String packageId = UUID.randomUUID().toString();
        ArtifactStorage.StoredArtifact storedArtifact = storage.store(path, packageId);
        StoredPackage stored = new StoredPackage(storedArtifact.packageId(), storedArtifact.reference());
        reviewService.submitValidatedPackage(result, stored, actor, requestId);
        return new UploadedPackage(packageId, result.skillId(), result.version(), "pending_review",
                result.sha256(), result.sizeBytes(), result.securityStatus(), result.securityFindings());
    }

    private long[] parseRange(String value) {
        Matcher matcher = value == null ? null : CONTENT_RANGE.matcher(value.trim());
        if (matcher == null || !matcher.matches()) {
            throw new ResumablePackageUploadException("UPLOAD_RANGE_INVALID", "Content-Range 范围无效", HttpStatus.BAD_REQUEST);
        }
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            if (end < start || end == Long.MAX_VALUE) {
                throw new NumberFormatException();
            }
            return new long[]{start, end, total};
        } catch (NumberFormatException invalid) {
            throw new ResumablePackageUploadException("UPLOAD_RANGE_INVALID", "Content-Range 范围无效", HttpStatus.BAD_REQUEST);
        }
    }

    private byte[] readAtMost(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[Math.min(maxBytes, 8192)];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }

    public record CreateUploadRequest(String fileName, long totalBytes) {
    }

    public record UploadedPackage(String packageId, String skillId, String version, String status, String sha256,
                                  long sizeBytes, String securityStatus,
                                  java.util.List<PackageSecurityFinding> securityFindings) {
    }
}
