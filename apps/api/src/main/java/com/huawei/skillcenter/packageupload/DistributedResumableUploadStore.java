package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.distribution.S3ObjectClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Multi-instance store: Redis owns upload state, S3-compatible storage owns immutable chunks. */
@Component
@ConditionalOnProperty(name = "skill-center.package-upload-backend", havingValue = "distributed")
public class DistributedResumableUploadStore implements ResumableUploadStore {
    private final long maxPackageBytes;
    private final long maxChunkBytes;
    private final Clock clock;
    private final RedisResumableUploadMetadataStore metadata;
    private final S3ObjectClient objects;

    @Autowired
    public DistributedResumableUploadStore(
            StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Qualifier("resumableUploadObjectClient")
            S3ObjectClient objects,
            @Value("${skill-center.package-max-bytes:20971520}") long maxPackageBytes,
            @Value("${skill-center.package-upload-chunk-bytes:1048576}") long maxChunkBytes,
            @Value("${skill-center.package-upload-session-ttl-seconds:1800}") long ttlSeconds,
            @Value("${skill-center.package-upload-max-sessions:100}") int maxActiveSessions,
            @Value("${skill-center.package-upload-max-active-bytes:536870912}") long maxActiveBytes) {
        this(maxPackageBytes, maxChunkBytes, new RedisResumableUploadMetadataStore(redis,
                "skill-center:resumable-uploads", Math.toIntExact(ttlSeconds), maxActiveSessions,
                maxActiveBytes, Clock.systemUTC()), objects, Clock.systemUTC());
    }

    public DistributedResumableUploadStore(long maxPackageBytes, long maxChunkBytes,
                                           RedisResumableUploadMetadataStore metadata,
                                           S3ObjectClient objects, Clock clock) {
        this.maxPackageBytes = maxPackageBytes;
        this.maxChunkBytes = maxChunkBytes;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public UploadProgress create(CreateRequest request) {
        validateCreate(request);
        metadata.cleanupExpired(clock.instant());
        String uploadId = java.util.UUID.randomUUID().toString();
        if (!metadata.create(uploadId, request)) {
            throw failure("UPLOAD_CAPACITY_EXCEEDED", "上传临时空间或会话容量已达上限");
        }
        return progress(uploadId);
    }

    @Override
    public UploadProgress load(String uploadId, String ownerId) {
        MetadataView view = view(uploadId);
        checkOwner(view, ownerId);
        metadata.touch(uploadId);
        return view.progress(maxChunkBytes);
    }

    @Override
    public UploadProgress progress(String uploadId) {
        MetadataView view = view(uploadId);
        metadata.touch(uploadId);
        return view.progress(maxChunkBytes);
    }

    @Override
    public UploadProgress append(AppendRequest request) {
        validateAppend(request);
        RedisResumableUploadMetadataStore.Reservation reservation = metadata.reserve(
                request.uploadId(), request.ownerId(), request.start(), request.end(), request.totalBytes());
        switch (reservation.result()) {
            case "NOT_FOUND" -> throw failure("UPLOAD_NOT_FOUND", "上传会话不存在或已过期");
            case "FORBIDDEN" -> throw failure("UPLOAD_FORBIDDEN", "上传会话不属于当前用户");
            case "TOTAL_MISMATCH" -> throw failure("UPLOAD_TOTAL_MISMATCH", "Content-Range 总大小与上传会话不一致");
            case "OFFSET_INVALID" -> throw failure("UPLOAD_OFFSET_INVALID", "分片必须从当前已接收位置继续上传");
            case "BUSY" -> throw failure("UPLOAD_LEASE_BUSY", "上传会话正在处理其他分片");
            case "IDEMPOTENT" -> {
                String existingKey = chunkKey(request.uploadId(), request.start(), request.end());
                Optional<byte[]> existing = objects.get(existingKey);
                if (existing.isEmpty() || !java.util.Arrays.equals(existing.get(), request.content())) {
                    throw failure("UPLOAD_CHUNK_CONFLICT", "上传分片已存在但内容不一致");
                }
                return progress(request.uploadId());
            }
            case "RESERVED" -> { }
            default -> throw failure("UPLOAD_METADATA_UNAVAILABLE", "上传元数据存储不可用");
        }

        String objectKey = chunkKey(request.uploadId(), request.start(), request.end());
        try {
            S3ObjectClient.PutResult result = objects.putIfAbsent(objectKey, request.content());
            if (!result.stored()) {
                Optional<byte[]> existing = objects.get(objectKey);
                if (existing.isEmpty() || !java.util.Arrays.equals(existing.get(), request.content())) {
                    throw failure("UPLOAD_CHUNK_CONFLICT", "上传分片已存在但内容不一致");
                }
            }
            metadata.commit(request.uploadId(), reservation, objectKey, request.start(), request.end());
            return progress(request.uploadId());
        } catch (ResumableUploadStore.StoreException exception) {
            cleanupUncommittedObject(request.uploadId(), objectKey, reservation, exception);
            throw exception;
        } catch (RuntimeException exception) {
            cleanupUncommittedObject(request.uploadId(), objectKey, reservation, exception);
            throw new ResumableUploadStore.StoreException("UPLOAD_STORAGE_UNAVAILABLE", "无法写入上传分片", exception);
        }
    }

    @Override
    public CompletedUpload complete(String uploadId, String ownerId) {
        MetadataView view = view(uploadId);
        checkOwner(view, ownerId);
        metadata.touch(uploadId);
        if ("completed".equals(view.state())) {
            return assemble(uploadId, view);
        }
        if (view.receivedBytes() != view.totalBytes()) {
            throw failure("UPLOAD_INCOMPLETE", "上传文件尚未接收完成");
        }
        CompletedUpload completed = assemble(uploadId, view);
        metadata.markCompleted(uploadId);
        return completed;
    }

    @Override
    public void requireOwner(String uploadId, String ownerId) {
        load(uploadId, ownerId);
    }

    @Override
    public void discard(String uploadId, String ownerId) {
        MetadataView view = view(uploadId);
        checkOwner(view, ownerId);
        List<String> chunks = metadata.chunkKeys(uploadId);
        metadata.discard(uploadId, ownerId);
        for (String chunk : chunks) {
            try {
                objects.delete(chunk);
            } catch (RuntimeException ignored) {
                // Metadata is authoritative; orphan cleanup can retry the object deletion.
            }
        }
    }

    @Override
    public int cleanupExpired(Instant now) {
        try {
            int removed = 0;
            for (String uploadId : metadata.expiredUploadIds(now)) {
                try {
                    discard(uploadId, null);
                    removed++;
                } catch (ResumableUploadStore.StoreException ignored) {
                    // A concurrent worker may already have removed the session.
                }
            }
            return removed;
        } catch (RuntimeException unavailable) {
            // Scheduled cleanup must remain quiet and retry after the shared metadata store recovers.
            return 0;
        }
    }

    @Override
    public int cleanupExpired() {
        return cleanupExpired(clock.instant());
    }

    @Override
    public Readiness readiness() {
        Readiness redisReady = metadata.readiness();
        boolean objectReady;
        try {
            objectReady = objects.readyForUse();
        } catch (RuntimeException unavailable) {
            objectReady = false;
        }
        return new Readiness("distributed", "READY".equals(redisReady.status()) && objectReady
                ? "READY" : "NOT_READY");
    }

    @Scheduled(fixedDelayString = "${skill-center.package-upload-cleanup-interval-ms:60000}")
    void scheduledCleanup() {
        cleanupExpired();
    }

    private CompletedUpload assemble(String uploadId, MetadataView view) {
        Path path = null;
        try {
            path = Files.createTempFile("skill-resumable-complete-", ".zip");
            long written = 0;
            for (String chunk : metadata.chunkKeys(uploadId)) {
                Optional<byte[]> bytes = objects.get(chunk);
                if (bytes.isEmpty()) throw failure("UPLOAD_STORAGE_UNAVAILABLE", "上传分片不存在");
                written += bytes.get().length;
                if (written > view.totalBytes()) throw failure("UPLOAD_RANGE_INVALID", "上传分片总大小超出会话大小");
                Files.write(path, bytes.get(), StandardOpenOption.APPEND);
            }
            if (written != view.totalBytes()) throw failure("UPLOAD_INCOMPLETE", "上传文件尚未接收完成");
            return new CompletedUpload(uploadId, view.fileName(), path);
        } catch (IOException exception) {
            deleteIfPresent(path);
            throw new ResumableUploadStore.StoreException("UPLOAD_STORAGE_UNAVAILABLE", "无法组装上传文件", exception);
        } catch (ResumableUploadStore.StoreException exception) {
            deleteIfPresent(path);
            throw exception;
        } catch (RuntimeException exception) {
            deleteIfPresent(path);
            throw new ResumableUploadStore.StoreException("UPLOAD_STORAGE_UNAVAILABLE", "无法读取上传分片", exception);
        }
    }

    private void deleteIfPresent(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A later temporary-file cleanup can retry the deletion.
        }
    }

    private MetadataView view(String uploadId) {
        RedisResumableUploadMetadataStore.Metadata value = metadata.load(uploadId);
        return new MetadataView(uploadId, value.fileName(), value.totalBytes(), value.ownerId(), value.receivedBytes(), value.state());
    }

    private void cleanupUncommittedObject(String uploadId, String objectKey,
                                          RedisResumableUploadMetadataStore.Reservation reservation,
                                          RuntimeException failure) {
        if (failure instanceof ResumableUploadStore.StoreException storeFailure
                && !"UPLOAD_LEASE_LOST".equals(storeFailure.code())) {
            try {
                metadata.abort(uploadId, reservation);
            } catch (RuntimeException ignored) {
                // The original storage failure is the actionable error.
            }
        } else if (!(failure instanceof ResumableUploadStore.StoreException)) {
            try {
                metadata.abort(uploadId, reservation);
            } catch (RuntimeException ignored) {
                // The original storage failure is the actionable error.
            }
        }
        try {
            if (!metadata.chunkKeys(uploadId).contains(objectKey)) {
                objects.delete(objectKey);
            }
        } catch (RuntimeException ignored) {
            // Orphan cleanup is retried by the next bounded cleanup pass.
        }
    }

    private void checkOwner(MetadataView view, String ownerId) {
        if (ownerId != null && !ownerId.equals(view.ownerId())) {
            throw failure("UPLOAD_FORBIDDEN", "上传会话不属于当前用户");
        }
    }

    private void validateCreate(CreateRequest request) {
        if (request == null || request.fileName() == null || request.fileName().isBlank()
                || !request.fileName().toLowerCase(Locale.ROOT).endsWith(".zip")
                || request.fileName().contains("/") || request.fileName().contains("\\")
                || request.fileName().length() > 255) throw failure("UPLOAD_FILE_INVALID", "只支持安全的 ZIP 文件名");
        if (request.totalBytes() <= 0 || request.totalBytes() > maxPackageBytes) {
            throw failure("UPLOAD_SIZE_INVALID", "上传文件大小超过平台限制");
        }
    }

    private void validateAppend(AppendRequest request) {
        if (request == null || request.start() < 0 || request.end() < request.start()
                || request.end() == Long.MAX_VALUE) throw failure("UPLOAD_RANGE_INVALID", "Content-Range 范围无效");
        long length = request.end() - request.start() + 1;
        if (length > maxChunkBytes) throw failure("UPLOAD_CHUNK_TOO_LARGE", "上传分片超过平台限制");
        if (request.content() == null || request.content().length != length) {
            throw failure("UPLOAD_RANGE_MISMATCH", "分片内容长度与 Content-Range 不一致");
        }
    }

    private String chunkKey(String uploadId, long start, long end) {
        return "resumable/" + uploadId + "/" + start + "-" + end;
    }

    private ResumableUploadStore.StoreException failure(String code, String message) {
        return new ResumableUploadStore.StoreException(code, message);
    }

    private record MetadataView(String uploadId, String fileName, long totalBytes, String ownerId, long receivedBytes, String state) {
        private UploadProgress progress(long chunkSize) {
            String status = switch (state) {
                case "created" -> "created";
                case "ready" -> "ready";
                case "completed" -> "completed";
                default -> receivedBytes == 0 ? "created" : "uploading";
            };
            return new UploadProgress(uploadId, fileName, totalBytes, receivedBytes, chunkSize, status);
        }
    }
}
