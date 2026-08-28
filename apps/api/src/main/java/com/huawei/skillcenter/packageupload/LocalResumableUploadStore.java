package com.huawei.skillcenter.packageupload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Single-instance upload store backed by local temporary files. */
@Component
@ConditionalOnProperty(name = "skill-center.package-upload-backend", havingValue = "local", matchIfMissing = true)
public class LocalResumableUploadStore implements ResumableUploadStore {
    private final long maxPackageBytes;
    private final long maxChunkBytes;
    private final long sessionTtlMillis;
    private final int maxActiveSessions;
    private final long maxActiveBytes;
    private final Clock clock;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Object capacityLock = new Object();
    private long activeBytes;

    @Autowired
    public LocalResumableUploadStore(
            @Value("${skill-center.package-max-bytes:20971520}") long maxPackageBytes,
            @Value("${skill-center.package-upload-chunk-bytes:1048576}") long maxChunkBytes,
            @Value("${skill-center.package-upload-session-ttl-seconds:1800}") long sessionTtlSeconds,
            @Value("${skill-center.package-upload-max-sessions:100}") int maxActiveSessions,
            @Value("${skill-center.package-upload-max-active-bytes:536870912}") long maxActiveBytes) {
        this(maxPackageBytes, maxChunkBytes, Duration.ofSeconds(sessionTtlSeconds),
                maxActiveSessions, maxActiveBytes, Clock.systemUTC());
    }

    public LocalResumableUploadStore(long maxPackageBytes, long maxChunkBytes, Duration sessionTtl,
                                     int maxActiveSessions, long maxActiveBytes, Clock clock) {
        if (maxPackageBytes <= 0 || maxChunkBytes <= 0 || maxActiveSessions <= 0 || maxActiveBytes <= 0
                || sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("upload limits must be positive");
        }
        long ttlMillis;
        try {
            ttlMillis = sessionTtl.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("upload session ttl is too large", exception);
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("upload session ttl must be at least one millisecond");
        }
        this.maxPackageBytes = maxPackageBytes;
        this.maxChunkBytes = maxChunkBytes;
        this.sessionTtlMillis = ttlMillis;
        this.maxActiveSessions = maxActiveSessions;
        this.maxActiveBytes = maxActiveBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResumableUploadStore.UploadProgress create(ResumableUploadStore.CreateRequest request) {
        if (request == null) {
            throw failure("UPLOAD_FILE_INVALID", "上传请求无效");
        }
        String fileName = request.fileName();
        if (fileName == null || fileName.isBlank() || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip")
                || fileName.contains("/") || fileName.contains("\\") || fileName.length() > 255) {
            throw failure("UPLOAD_FILE_INVALID", "只支持安全的 ZIP 文件名");
        }
        if (request.totalBytes() <= 0 || request.totalBytes() > maxPackageBytes) {
            throw failure("UPLOAD_SIZE_INVALID", "上传文件大小超过平台限制");
        }
        cleanupExpired(clock.instant());
        synchronized (capacityLock) {
            if (sessions.size() >= maxActiveSessions || activeBytes > maxActiveBytes - request.totalBytes()) {
                throw failure("UPLOAD_CAPACITY_EXCEEDED", "上传临时空间或会话容量已达上限");
            }
            try {
                Path path = Files.createTempFile("skill-resumable-", ".zip");
                String uploadId = UUID.randomUUID().toString();
                Session session = new Session(uploadId, fileName, request.totalBytes(), path,
                        normalizeOwner(request.ownerId()));
                sessions.put(uploadId, session);
                activeBytes += request.totalBytes();
                return session.progress();
            } catch (IOException exception) {
                throw failure("UPLOAD_STORAGE_UNAVAILABLE", "无法创建上传会话", exception);
            }
        }
    }

    @Override
    public ResumableUploadStore.UploadProgress load(String uploadId, String ownerId) {
        Session session = activeSession(uploadId);
        synchronized (session) {
            checkOwner(session, ownerId);
            session.touch(clock.millis());
            return session.progress();
        }
    }

    @Override
    public ResumableUploadStore.UploadProgress progress(String uploadId) {
        Session session = activeSession(uploadId);
        synchronized (session) {
            session.touch(clock.millis());
            return session.progress();
        }
    }

    @Override
    public ResumableUploadStore.UploadProgress append(ResumableUploadStore.AppendRequest request) {
        if (request == null) {
            throw failure("UPLOAD_RANGE_INVALID", "分片请求无效");
        }
        Session session = activeSession(request.uploadId());
        synchronized (session) {
            checkOwner(session, request.ownerId());
            if (session.completed) {
                throw failure("UPLOAD_ALREADY_COMPLETED", "上传会话已经完成");
            }
            if (request.totalBytes() != session.totalBytes) {
                throw failure("UPLOAD_TOTAL_MISMATCH", "Content-Range 总大小与上传会话不一致");
            }
            if (request.start() < 0 || request.end() < request.start() || request.end() == Long.MAX_VALUE) {
                throw failure("UPLOAD_RANGE_INVALID", "Content-Range 范围无效");
            }
            long expectedLength = request.end() - request.start() + 1;
            if (expectedLength > maxChunkBytes) {
                throw failure("UPLOAD_CHUNK_TOO_LARGE", "上传分片超过平台限制");
            }
            if (request.content() == null || request.content().length != expectedLength) {
                throw failure("UPLOAD_RANGE_MISMATCH", "分片内容长度与 Content-Range 不一致");
            }
            if (request.start() != session.receivedBytes) {
                throw failure("UPLOAD_OFFSET_INVALID", "分片必须从当前已接收位置继续上传");
            }
            if (request.end() >= session.totalBytes) {
                throw failure("UPLOAD_RANGE_INVALID", "分片超出上传文件大小");
            }
            try {
                Files.write(session.path, request.content(), StandardOpenOption.APPEND);
                session.receivedBytes += request.content().length;
                session.touch(clock.millis());
                return session.progress();
            } catch (IOException exception) {
                throw failure("UPLOAD_STORAGE_UNAVAILABLE", "无法写入上传分片", exception);
            }
        }
    }

    @Override
    public ResumableUploadStore.CompletedUpload complete(String uploadId, String ownerId) {
        Session session = activeSession(uploadId);
        synchronized (session) {
            checkOwner(session, ownerId);
            if (session.receivedBytes != session.totalBytes) {
                throw failure("UPLOAD_INCOMPLETE", "上传文件尚未接收完成");
            }
            session.touch(clock.millis());
            session.completed = true;
            return new ResumableUploadStore.CompletedUpload(session.uploadId, session.fileName, session.path);
        }
    }

    @Override
    public void requireOwner(String uploadId, String ownerId) {
        load(uploadId, ownerId);
    }

    @Override
    public void discard(String uploadId, String ownerId) {
        Session session;
        synchronized (capacityLock) {
            session = uploadId == null ? null : sessions.get(uploadId);
            if (session != null) {
                synchronized (session) {
                    checkOwner(session, ownerId);
                    if (sessions.remove(uploadId, session)) {
                        activeBytes -= session.totalBytes;
                    }
                }
            }
        }
        if (session != null) {
            deleteTemporaryFile(session);
        }
    }

    @Override
    public int cleanupExpired(Instant now) {
        long nowMillis = Objects.requireNonNull(now, "now").toEpochMilli();
        List<Session> expired = new ArrayList<>();
        synchronized (capacityLock) {
            for (Session session : sessions.values()) {
                synchronized (session) {
                    if (nowMillis - session.lastActivityMillis >= sessionTtlMillis
                            && sessions.remove(session.uploadId, session)) {
                        activeBytes -= session.totalBytes;
                        expired.add(session);
                    }
                }
            }
        }
        expired.forEach(this::deleteTemporaryFile);
        return expired.size();
    }

    @Override
    public int cleanupExpired() {
        return cleanupExpired(clock.instant());
    }

    @Override
    public ResumableUploadStore.Readiness readiness() {
        return new ResumableUploadStore.Readiness("local", "LOCAL_ONLY");
    }

    @Scheduled(fixedDelayString = "${skill-center.package-upload-cleanup-interval-ms:60000}")
    void scheduledCleanup() {
        cleanupExpired(clock.instant());
    }

    private Session activeSession(String uploadId) {
        Session session = uploadId == null ? null : sessions.get(uploadId);
        if (session == null) {
            throw failure("UPLOAD_NOT_FOUND", "上传会话不存在或已过期");
        }
        return session;
    }

    private void checkOwner(Session session, String ownerId) {
        if (ownerId != null && !ownerId.equals(session.ownerId)) {
            throw failure("UPLOAD_FORBIDDEN", "上传会话不属于当前用户");
        }
    }

    private String normalizeOwner(String ownerId) {
        return ownerId == null || ownerId.isBlank() ? "local-user" : ownerId;
    }

    private ResumableUploadStore.StoreException failure(String code, String message) {
        return new ResumableUploadStore.StoreException(code, message);
    }

    private ResumableUploadStore.StoreException failure(String code, String message, Throwable cause) {
        return new ResumableUploadStore.StoreException(code, message, cause);
    }

    private void deleteTemporaryFile(Session session) {
        try {
            Files.deleteIfExists(session.path);
        } catch (IOException ignored) {
            // Cleanup is retried by the next scheduled pass.
        }
    }

    private final class Session {
        private final String uploadId;
        private final String fileName;
        private final long totalBytes;
        private final Path path;
        private final String ownerId;
        private long lastActivityMillis;
        private long receivedBytes;
        private boolean completed;

        private Session(String uploadId, String fileName, long totalBytes, Path path, String ownerId) {
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.totalBytes = totalBytes;
            this.path = path;
            this.ownerId = ownerId;
            this.lastActivityMillis = clock.millis();
        }

        private void touch(long now) {
            lastActivityMillis = now;
        }

        private ResumableUploadStore.UploadProgress progress() {
            return new ResumableUploadStore.UploadProgress(uploadId, fileName, totalBytes, receivedBytes,
                    maxChunkBytes, completed ? "completed" : receivedBytes == 0 ? "created"
                    : receivedBytes == totalBytes ? "ready" : "uploading");
        }
    }
}
