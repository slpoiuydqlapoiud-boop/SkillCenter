package com.huawei.skillcenter.api;

import com.huawei.skillcenter.packageupload.PackageValidationException;
import com.huawei.skillcenter.distribution.ArtifactNotFoundException;
import com.huawei.skillcenter.distribution.DistributionAuthorizationException;
import com.huawei.skillcenter.events.InvocationEventConflictException;
import com.huawei.skillcenter.analytics.InvalidAnalyticsQueryException;
import com.huawei.skillcenter.governance.ForbiddenException;
import com.huawei.skillcenter.governance.ConfigConflictException;
import com.huawei.skillcenter.governance.CollectionNotFoundException;
import com.huawei.skillcenter.governance.InvalidLifecycleRequestException;
import com.huawei.skillcenter.governance.InstallationNotFoundException;
import com.huawei.skillcenter.governance.ReviewStateConflictException;
import com.huawei.skillcenter.governance.SkillVersionNotFoundException;
import com.huawei.skillcenter.governance.VersionStateConflictException;
import com.huawei.skillcenter.distribution.WithdrawnVersionUnavailableException;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.ExportException;
import com.huawei.skillcenter.governance.RetentionException;
import com.huawei.skillcenter.skill.SkillNotFoundException;
import com.huawei.skillcenter.security.IdempotencyException;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import com.huawei.skillcenter.operations.InvalidOperationsQueryException;
import com.huawei.skillcenter.operations.MetricsTokenException;
import com.huawei.skillcenter.operations.InvalidOperationsAlertQueryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Set<String> SECURITY_EVENT_CODES = Set.of(
            "IDEMPOTENCY_REPLAY", "IDEMPOTENCY_CONFLICT", "EXPORT_QUEUE_FULL");
    private final OperationsMetricsService metrics;

    public GlobalExceptionHandler(OperationsMetricsService metrics) {
        this.metrics = metrics;
    }
    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<ErrorEnvelope> notFound(NoHandlerFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorEnvelope> invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiError.ErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiError.ErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request parameters are invalid", request, details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorEnvelope> invalidParameter(MethodArgumentTypeMismatchException exception,
                                                    HttpServletRequest request) {
        String field = exception.getName() == null ? "parameter" : exception.getName();
        String message = "Parameter must use the expected type";
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request parameters are invalid", request,
                List.of(new ApiError.ErrorDetail(field, message)));
    }

    @ExceptionHandler(SkillNotFoundException.class)
    ResponseEntity<ErrorEnvelope> skillNotFound(SkillNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill not found or not published", request, List.of());
    }

    @ExceptionHandler(InstallationNotFoundException.class)
    ResponseEntity<ErrorEnvelope> installationNotFound(InstallationNotFoundException exception,
                                                        HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "INSTALLATION_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(SkillVersionNotFoundException.class)
    ResponseEntity<ErrorEnvelope> skillVersionNotFound(SkillVersionNotFoundException exception,
                                                        HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SKILL_VERSION_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(VersionStateConflictException.class)
    ResponseEntity<ErrorEnvelope> versionStateConflict(VersionStateConflictException exception,
                                                        HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "VERSION_STATE_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ConfigConflictException.class)
    ResponseEntity<ErrorEnvelope> configConflict(ConfigConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFIG_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(CollectionNotFoundException.class)
    ResponseEntity<ErrorEnvelope> collectionNotFound(CollectionNotFoundException exception,
                                                      HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "COLLECTION_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidLifecycleRequestException.class)
    ResponseEntity<ErrorEnvelope> invalidLifecycleRequest(InvalidLifecycleRequestException exception,
                                                           HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(WithdrawnVersionUnavailableException.class)
    ResponseEntity<ErrorEnvelope> withdrawnVersionUnavailable(WithdrawnVersionUnavailableException exception,
                                                               HttpServletRequest request) {
        return error(HttpStatus.GONE, "WITHDRAWN_VERSION_UNAVAILABLE", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ArtifactNotFoundException.class)
    ResponseEntity<ErrorEnvelope> artifactNotFound(ArtifactNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DistributionAuthorizationException.class)
    ResponseEntity<ErrorEnvelope> distributionAuthorization(DistributionAuthorizationException exception,
                                                              HttpServletRequest request) {
        boolean malformed = exception.getMessage() != null && exception.getMessage().contains("malformed");
        return error(malformed ? HttpStatus.BAD_REQUEST : HttpStatus.GONE,
                "DISTRIBUTION_AUTHORIZATION_INVALID", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvocationEventConflictException.class)
    ResponseEntity<ErrorEnvelope> invocationEventConflict(InvocationEventConflictException exception,
                                                           HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "EVENT_ID_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(PackageValidationException.class)
    ResponseEntity<ErrorEnvelope> packageValidation(PackageValidationException exception, HttpServletRequest request) {
        List<ApiError.ErrorDetail> details = exception.result().errors().stream()
                .map(validationError -> new ApiError.ErrorDetail(validationError.path(), validationError.reason()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "PACKAGE_VALIDATION_FAILED", "Skill package validation failed", request, details);
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ErrorEnvelope> forbidden(ForbiddenException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ErrorEnvelope> idempotency(IdempotencyException exception, HttpServletRequest request) {
        HttpStatus status = "IDEMPOTENCY_KEY_INVALID".equals(exception.code())
                ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return error(status, exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExportException.class)
    ResponseEntity<ErrorEnvelope> exportException(ExportException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "EXPORT_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "EXPORT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "EXPORT_EXPIRED" -> HttpStatus.GONE;
            case "EXPORT_NOT_READY", "EXPORT_FILTER_INVALID" -> HttpStatus.CONFLICT;
            case "EXPORT_QUEUE_FULL" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return error(status, exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(RetentionException.class)
    ResponseEntity<ErrorEnvelope> retentionException(RetentionException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "RETENTION_PREVIEW_EXPIRED" -> HttpStatus.GONE;
            case "RETENTION_POLICY_CONFLICT", "RETENTION_EXECUTION_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ReviewStateConflictException.class)
    ResponseEntity<ErrorEnvelope> reviewConflict(ReviewStateConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "REVIEW_STATE_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(GovernanceStore.GovernancePersistenceException.class)
    ResponseEntity<ErrorEnvelope> governancePersistence(GovernanceStore.GovernancePersistenceException exception,
                                                         HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "GOVERNANCE_PERSISTENCE_FAILED",
                "Governance state is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorEnvelope> unreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "EVENT_SCHEMA_INVALID", "Request body does not match the allowed event schema", request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorEnvelope> illegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "EVENT_SCHEMA_INVALID", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidAnalyticsQueryException.class)
    ResponseEntity<ErrorEnvelope> invalidAnalyticsQuery(InvalidAnalyticsQueryException exception,
                                                          HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidOperationsQueryException.class)
    ResponseEntity<ErrorEnvelope> invalidOperationsQuery(InvalidOperationsQueryException exception,
                                                           HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MetricsTokenException.class)
    ResponseEntity<ErrorEnvelope> metricsToken(MetricsTokenException exception, HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidOperationsAlertQueryException.class)
    ResponseEntity<ErrorEnvelope> invalidOperationsAlertQuery(InvalidOperationsAlertQueryException exception,
                                                               HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> internalError(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Service temporarily unavailable", request, List.of());
    }

    private ResponseEntity<ErrorEnvelope> error(HttpStatus status, String code, String message,
                                                HttpServletRequest request, List<ApiError.ErrorDetail> details) {
        if (SECURITY_EVENT_CODES.contains(code)) {
            metrics.recordSecurityEvent(code);
        }
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String resolvedRequestId = requestId == null ? "unknown" : requestId.toString();
        return ResponseEntity.status(status).body(new ErrorEnvelope(
                new ApiError(code, message, details), resolvedRequestId));
    }

    public record ErrorEnvelope(ApiError error, String requestId) {
    }
}
