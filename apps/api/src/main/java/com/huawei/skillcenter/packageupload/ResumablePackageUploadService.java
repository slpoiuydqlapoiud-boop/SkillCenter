package com.huawei.skillcenter.packageupload;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/** Application facade for resumable uploads; storage is selected behind a stable port. */
@Service
public class ResumablePackageUploadService {
    private final ResumableUploadStore store;

    @Autowired
    public ResumablePackageUploadService(ResumableUploadStore store) {
        this.store = store;
    }

    public ResumablePackageUploadService(long maxPackageBytes, long maxChunkBytes) {
        this(new LocalResumableUploadStore(maxPackageBytes, maxChunkBytes, Duration.ofMinutes(30),
                100, 512L * 1024 * 1024, Clock.systemUTC()));
    }

    public ResumablePackageUploadService(long maxPackageBytes, long maxChunkBytes,
                                         Duration sessionTtl, int maxActiveSessions,
                                         long maxActiveBytes, Clock clock) {
        this(new LocalResumableUploadStore(maxPackageBytes, maxChunkBytes, sessionTtl,
                maxActiveSessions, maxActiveBytes, clock));
    }

    public UploadProgress create(String fileName, long totalBytes) {
        return create(fileName, totalBytes, "local-user");
    }

    public UploadProgress create(String fileName, long totalBytes, String ownerId) {
        try {
            return from(store.create(new ResumableUploadStore.CreateRequest(fileName, totalBytes, ownerId)));
        } catch (ResumableUploadStore.StoreException exception) {
            throw translate(exception);
        }
    }

    public UploadProgress append(String uploadId, long start, long end, long totalBytes, byte[] chunk) {
        try {
            return from(store.append(new ResumableUploadStore.AppendRequest(
                    uploadId, null, start, end, totalBytes, chunk)));
        } catch (ResumableUploadStore.StoreException exception) {
            throw translate(exception);
        }
    }

    public UploadProgress progress(String uploadId) {
        try {
            return from(store.progress(uploadId));
        } catch (ResumableUploadStore.StoreException exception) {
            throw translate(exception);
        }
    }

    public void requireOwner(String uploadId, String ownerId) {
        execute(() -> store.requireOwner(uploadId, ownerId));
    }

    public CompletedUpload complete(String uploadId) {
        try {
            return from(store.complete(uploadId, null));
        } catch (ResumableUploadStore.StoreException exception) {
            throw translate(exception);
        }
    }

    public void discard(String uploadId) {
        execute(() -> store.discard(uploadId, null));
    }

    public int cleanupExpiredSessions() {
        try {
            return store.cleanupExpired();
        } catch (ResumableUploadStore.StoreException exception) {
            throw translate(exception);
        }
    }

    private UploadProgress from(ResumableUploadStore.UploadProgress progress) {
        return new UploadProgress(progress.uploadId(), progress.fileName(), progress.totalBytes(),
                progress.receivedBytes(), progress.chunkSize(), progress.status());
    }

    private CompletedUpload from(ResumableUploadStore.CompletedUpload upload) {
        return new CompletedUpload(upload.uploadId(), upload.fileName(), upload.path());
    }

    private void execute(Runnable operation) {
        try {
            operation.run();
        } catch (ResumableUploadStore.StoreException exception) {
            throw translate(exception);
        }
    }

    private ResumablePackageUploadException translate(ResumableUploadStore.StoreException exception) {
        HttpStatus status = switch (exception.code()) {
            case "UPLOAD_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "UPLOAD_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "UPLOAD_CAPACITY_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "UPLOAD_ALREADY_COMPLETED", "UPLOAD_INCOMPLETE", "UPLOAD_TOTAL_MISMATCH",
                    "UPLOAD_OFFSET_INVALID" -> HttpStatus.CONFLICT;
            case "UPLOAD_STORAGE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return new ResumablePackageUploadException(exception.code(), exception.getMessage(), status);
    }

    public record UploadProgress(String uploadId, String fileName, long totalBytes, long receivedBytes,
                                 long chunkSize, String status) {
    }

    public record CompletedUpload(String uploadId, String fileName, Path path) {
    }
}
