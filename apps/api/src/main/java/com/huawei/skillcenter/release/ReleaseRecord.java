package com.huawei.skillcenter.release;

import java.time.Instant;
import java.util.Locale;

public record ReleaseRecord(
        String releaseId,
        String skillId,
        String version,
        String sha256,
        ReleaseEnvironment targetEnvironment,
        ReleaseGateSnapshot gateSnapshot,
        String sourceAssessmentId,
        String rollbackOfReleaseId,
        String rollbackTargetVersion,
        String rollbackTargetReleaseId,
        String rollbackAssessmentId,
        String idempotencyKey,
        ReleaseStatus status,
        String requestedBy,
        Instant requestedAt,
        String approvedBy,
        Instant approvedAt,
        String statusReason,
        String targetReference,
        Instant startedAt,
        Instant completedAt,
        String updatedBy,
        Instant updatedAt
) {
    public ReleaseRecord {
        releaseId = requireIdentifier(releaseId, "releaseId");
        skillId = requireIdentifier(skillId, "skillId");
        version = requireText(version, "version", 128);
        sha256 = requireSha256(sha256);
        if (targetEnvironment == null) throw new IllegalArgumentException("targetEnvironment is required");
        if (gateSnapshot == null) throw new IllegalArgumentException("gateSnapshot is required");
        sourceAssessmentId = optionalIdentifier(sourceAssessmentId, "sourceAssessmentId");
        rollbackOfReleaseId = optionalIdentifier(rollbackOfReleaseId, "rollbackOfReleaseId");
        rollbackTargetVersion = optionalText(rollbackTargetVersion, "rollbackTargetVersion", 128);
        rollbackTargetReleaseId = optionalIdentifier(rollbackTargetReleaseId, "rollbackTargetReleaseId");
        rollbackAssessmentId = optionalIdentifier(rollbackAssessmentId, "rollbackAssessmentId");
        idempotencyKey = requireIdentifier(idempotencyKey, "idempotencyKey");
        if (status == null) throw new IllegalArgumentException("status is required");
        requestedBy = requireIdentifier(requestedBy, "requestedBy");
        requestedAt = requiredInstant(requestedAt, "requestedAt");
        approvedBy = optionalIdentifier(approvedBy, "approvedBy");
        if (approvedBy.isBlank() != (approvedAt == null)) {
            throw new IllegalArgumentException("approvedBy and approvedAt must be provided together");
        }
        statusReason = optionalText(statusReason, "statusReason", 512);
        targetReference = optionalText(targetReference, "targetReference", 256);
        if (startedAt != null && startedAt.isBefore(requestedAt)) throw new IllegalArgumentException("startedAt is invalid");
        if (completedAt != null && completedAt.isBefore(requestedAt)) throw new IllegalArgumentException("completedAt is invalid");
        updatedBy = requireIdentifier(updatedBy, "updatedBy");
        updatedAt = requiredInstant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(requestedAt)) throw new IllegalArgumentException("updatedAt must not be before requestedAt");
        if (ReleaseEnvironment.PRODUCTION.equals(targetEnvironment) && "NO_EVIDENCE".equals(gateSnapshot.outcome())) {
            throw new IllegalArgumentException("PRODUCTION release cannot use NO_EVIDENCE");
        }
        validateStatusFields();
    }

    public static ReleaseRecord request(String releaseId, String skillId, String version, String sha256,
                                        ReleaseEnvironment environment, ReleaseGateSnapshot gateSnapshot,
                                        String idempotencyKey, String requestedBy, Instant requestedAt) {
        return new ReleaseRecord(releaseId, skillId, version, sha256, environment, gateSnapshot, "", "", "", "", "",
                idempotencyKey, ReleaseStatus.REQUESTED, requestedBy, requestedAt, "", null, "", "", null, null,
                requestedBy, requestedAt);
    }

    public ReleaseRecord approve(String actor, Instant at) {
        requireTransition(ReleaseStatus.APPROVED);
        if (requestedBy.equals(actor)) throw new IllegalStateException("requester cannot approve release");
        return copy(ReleaseStatus.APPROVED, actor, at, actor, at, "", "", null, null, rollbackAssessmentId);
    }

    public ReleaseRecord reject(String actor, String reason, Instant at) {
        requireTransition(ReleaseStatus.REJECTED);
        String normalizedReason = requireText(reason, "reason", 512);
        return copy(ReleaseStatus.REJECTED, actor, at, approvedBy, approvedAt, normalizedReason, "", null, at,
                rollbackAssessmentId);
    }

    public ReleaseRecord promoting(String targetReference, Instant at) {
        requireTransition(ReleaseStatus.PROMOTING);
        return copy(ReleaseStatus.PROMOTING, updatedBy, at, approvedBy, approvedAt, "", optionalText(targetReference,
                "targetReference", 256), at, null, rollbackAssessmentId);
    }

    public ReleaseRecord promoted(String targetReference, Instant at) {
        requireTransition(ReleaseStatus.PROMOTED);
        return copy(ReleaseStatus.PROMOTED, updatedBy, at, approvedBy, approvedAt, "", optionalText(targetReference,
                "targetReference", 256), startedAt, at, rollbackAssessmentId);
    }

    public ReleaseRecord failed(String reasonCode, String targetReference, Instant at) {
        if (!(status == ReleaseStatus.PROMOTING || status == ReleaseStatus.ROLLING_BACK)) {
            throw new IllegalStateException("release execution can only fail while executing");
        }
        return copy(ReleaseStatus.FAILED, updatedBy, at, approvedBy, approvedAt,
                requireCode(reasonCode, "reasonCode"), optionalText(targetReference, "targetReference", 256),
                startedAt, at, rollbackAssessmentId);
    }

    public ReleaseRecord rollbackReview(String actor, String reason, String assessmentId,
                                        String targetVersion, String targetReleaseId, Instant at) {
        requireTransition(ReleaseStatus.ROLLBACK_REVIEW);
        String normalizedReason = requireText(reason, "reason", 512);
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
        if ((targetVersion == null || targetVersion.isBlank()) && (targetReleaseId == null || targetReleaseId.isBlank())) {
            throw new IllegalArgumentException("rollback targetVersion or targetReleaseId is required");
        }
        return copy(ReleaseStatus.ROLLBACK_REVIEW, actor, at, approvedBy, approvedAt, normalizedReason,
                targetReference, null, null, optionalIdentifier(assessmentId, "rollbackAssessmentId"),
                optionalText(targetVersion, "rollbackTargetVersion", 128),
                optionalIdentifier(targetReleaseId, "rollbackTargetReleaseId"));
    }

    public ReleaseRecord rollingBack(Instant at) {
        requireTransition(ReleaseStatus.ROLLING_BACK);
        return copy(ReleaseStatus.ROLLING_BACK, updatedBy, at, approvedBy, approvedAt, statusReason,
                targetReference, at, null, rollbackAssessmentId);
    }

    public ReleaseRecord rolledBack(String targetReference, Instant at) {
        requireTransition(ReleaseStatus.ROLLED_BACK);
        return copy(ReleaseStatus.ROLLED_BACK, updatedBy, at, approvedBy, approvedAt, statusReason,
                optionalText(targetReference, "targetReference", 256), startedAt, at, rollbackAssessmentId);
    }

    private ReleaseRecord copy(ReleaseStatus nextStatus, String nextUpdatedBy, Instant nextUpdatedAt,
                               String nextApprovedBy, Instant nextApprovedAt, String nextReason,
                               String nextTargetReference, Instant nextStartedAt, Instant nextCompletedAt,
                               String nextRollbackAssessmentId) {
        return copy(nextStatus, nextUpdatedBy, nextUpdatedAt, nextApprovedBy, nextApprovedAt, nextReason,
                nextTargetReference, nextStartedAt, nextCompletedAt, nextRollbackAssessmentId,
                rollbackTargetVersion, rollbackTargetReleaseId);
    }

    private ReleaseRecord copy(ReleaseStatus nextStatus, String nextUpdatedBy, Instant nextUpdatedAt,
                               String nextApprovedBy, Instant nextApprovedAt, String nextReason,
                               String nextTargetReference, Instant nextStartedAt, Instant nextCompletedAt,
                               String nextRollbackAssessmentId, String nextRollbackTargetVersion,
                               String nextRollbackTargetReleaseId) {
        return new ReleaseRecord(releaseId, skillId, version, sha256, targetEnvironment, gateSnapshot,
                sourceAssessmentId, rollbackOfReleaseId, nextRollbackTargetVersion, nextRollbackTargetReleaseId,
                nextRollbackAssessmentId, idempotencyKey, nextStatus, requestedBy, requestedAt, nextApprovedBy,
                nextApprovedAt, nextReason, nextTargetReference, nextStartedAt, nextCompletedAt,
                nextUpdatedBy, nextUpdatedAt);
    }

    private void requireTransition(ReleaseStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("release cannot transition from " + status + " to " + target);
        }
    }

    private void validateStatusFields() {
        if (status == ReleaseStatus.APPROVED && approvedBy.isBlank()) {
            throw new IllegalArgumentException("APPROVED release requires approver");
        }
        if (status == ReleaseStatus.PROMOTED && completedAt == null) {
            throw new IllegalArgumentException("PROMOTED release requires completedAt");
        }
        if (status == ReleaseStatus.ROLLED_BACK && completedAt == null) {
            throw new IllegalArgumentException("ROLLED_BACK release requires completedAt");
        }
        if (status == ReleaseStatus.FAILED && statusReason.isBlank()) {
            throw new IllegalArgumentException("FAILED release requires statusReason");
        }
    }

    private static String requireSha256(String value) {
        String normalized = requireText(value, "sha256", 128).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{64}") && !normalized.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("sha256 must be a bounded digest");
        }
        return normalized;
    }

    private static String requireCode(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 64 || !normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a stable bounded code");
        }
        return normalized;
    }

    private static String requireIdentifier(String value, String field) {
        String normalized = requireText(value, field, 128);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String optionalIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()) requireIdentifier(normalized, field);
        return normalized;
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(field + " exceeds maximum length");
        return normalized;
    }

    private static String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) return "";
        return requireText(value, field, max);
    }

    private static Instant requiredInstant(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
