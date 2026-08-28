package com.huawei.skillcenter.packageupload;

import java.nio.file.Path;
import java.time.Instant;

/** Storage boundary for resumable uploads; implementations may be local or distributed. */
public interface ResumableUploadStore {
    UploadProgress create(CreateRequest request);

    UploadProgress load(String uploadId, String ownerId);

    UploadProgress progress(String uploadId);

    UploadProgress append(AppendRequest request);

    CompletedUpload complete(String uploadId, String ownerId);

    void requireOwner(String uploadId, String ownerId);

    void discard(String uploadId, String ownerId);

    int cleanupExpired(Instant now);

    default int cleanupExpired() {
        return cleanupExpired(Instant.now());
    }

    Readiness readiness();

    record CreateRequest(String fileName, long totalBytes, String ownerId) {
    }

    record AppendRequest(String uploadId, String ownerId, long start, long end,
                         long totalBytes, byte[] content) {
    }

    record UploadProgress(String uploadId, String fileName, long totalBytes, long receivedBytes,
                          long chunkSize, String status) {
    }

    record CompletedUpload(String uploadId, String fileName, Path path) {
    }

    record Readiness(String backend, String status) {
    }

    final class StoreException extends RuntimeException {
        private final String code;

        public StoreException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public StoreException(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
