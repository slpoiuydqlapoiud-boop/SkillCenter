package com.huawei.skillcenter.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ExportJob(
        UUID jobId,
        ExportDataset dataset,
        ExportFormat format,
        ExportFilters filters,
        String requestedBy,
        String requestedRole,
        String requestId,
        ExportJobStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime expiresAt,
        String artifactPath,
        long rowCount,
        String sha256,
        List<String> redactedFields,
        String downloadTokenHash,
        OffsetDateTime downloadTokenExpiresAt,
        OffsetDateTime downloadTokenConsumedAt,
        String failureCode,
        String failureMessage
) {
    public ExportJob {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required");
        }
        if (dataset == null) {
            throw new IllegalArgumentException("dataset is required");
        }
        if (format == null) {
            throw new IllegalArgumentException("format is required");
        }
        filters = filters == null ? ExportFilters.empty() : filters;
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy is required");
        }
        if (requestedRole == null || requestedRole.isBlank()) {
            throw new IllegalArgumentException("requestedRole is required");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        status = status == null ? ExportJobStatus.QUEUED : status;
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        if (expiresAt == null || expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt must not be before createdAt");
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must not be negative");
        }
        redactedFields = List.copyOf(redactedFields == null ? List.of() : redactedFields);
    }

    public ExportJob transitionTo(ExportJobStatus next, OffsetDateTime at) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalArgumentException("invalid export status transition: " + status + " -> " + next);
        }
        OffsetDateTime timestamp = at == null ? OffsetDateTime.now() : at;
        OffsetDateTime nextStartedAt = startedAt;
        OffsetDateTime nextCompletedAt = completedAt;
        if (next == ExportJobStatus.RUNNING && nextStartedAt == null) {
            nextStartedAt = timestamp;
        }
        if (next == ExportJobStatus.COMPLETED || next == ExportJobStatus.FAILED || next == ExportJobStatus.EXPIRED) {
            nextCompletedAt = timestamp;
        }
        return new ExportJob(jobId, dataset, format, filters, requestedBy, requestedRole, requestId,
                next, createdAt, nextStartedAt, nextCompletedAt, expiresAt, artifactPath, rowCount,
                sha256, redactedFields, downloadTokenHash, downloadTokenExpiresAt,
                downloadTokenConsumedAt, failureCode, failureMessage);
    }

    public ExportJob completed(String artifactPath, long rowCount, String sha256,
                               List<String> redactedFields, OffsetDateTime at) {
        if (status != ExportJobStatus.RUNNING) {
            throw new IllegalArgumentException("only running export jobs can complete");
        }
        OffsetDateTime timestamp = at == null ? OffsetDateTime.now() : at;
        return new ExportJob(jobId, dataset, format, filters, requestedBy, requestedRole, requestId,
                ExportJobStatus.COMPLETED, createdAt, startedAt, timestamp, expiresAt, artifactPath,
                rowCount, sha256, redactedFields, downloadTokenHash, downloadTokenExpiresAt,
                downloadTokenConsumedAt, null, null);
    }

    public ExportJob failed(String failureCode, String failureMessage, OffsetDateTime at) {
        if (status != ExportJobStatus.RUNNING && status != ExportJobStatus.QUEUED) {
            throw new IllegalArgumentException("only queued or running export jobs can fail");
        }
        OffsetDateTime timestamp = at == null ? OffsetDateTime.now() : at;
        return new ExportJob(jobId, dataset, format, filters, requestedBy, requestedRole, requestId,
                ExportJobStatus.FAILED, createdAt, startedAt, timestamp, expiresAt, artifactPath,
                rowCount, sha256, redactedFields, downloadTokenHash, downloadTokenExpiresAt,
                downloadTokenConsumedAt, failureCode, failureMessage);
    }

    public ExportJob withDownloadToken(String tokenHash, OffsetDateTime tokenExpiresAt) {
        return new ExportJob(jobId, dataset, format, filters, requestedBy, requestedRole, requestId,
                status, createdAt, startedAt, completedAt, expiresAt, artifactPath, rowCount, sha256,
                redactedFields, tokenHash, tokenExpiresAt, null, failureCode, failureMessage);
    }

    public ExportJob consumeDownload(OffsetDateTime consumedAt) {
        return new ExportJob(jobId, dataset, format, filters, requestedBy, requestedRole, requestId,
                status, createdAt, startedAt, completedAt, expiresAt, artifactPath, rowCount, sha256,
                redactedFields, downloadTokenHash, downloadTokenExpiresAt,
                consumedAt == null ? OffsetDateTime.now() : consumedAt, failureCode, failureMessage);
    }
}
