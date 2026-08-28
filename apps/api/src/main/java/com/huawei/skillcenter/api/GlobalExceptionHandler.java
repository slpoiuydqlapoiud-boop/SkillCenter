package com.huawei.skillcenter.api;

import com.huawei.skillcenter.packageupload.PackageValidationException;
import com.huawei.skillcenter.packageupload.ResumablePackageUploadException;
import com.huawei.skillcenter.access.SkillManageForbiddenException;
import com.huawei.skillcenter.access.SkillNotVisibleException;
import com.huawei.skillcenter.access.SkillAuthorizationService;
import com.huawei.skillcenter.access.SkillScopeConflictException;
import com.huawei.skillcenter.access.SkillScopeInvalidException;
import com.huawei.skillcenter.access.SkillScopeNotFoundException;
import com.huawei.skillcenter.access.SkillScopePersistenceException;
import com.huawei.skillcenter.distribution.ArtifactNotFoundException;
import com.huawei.skillcenter.distribution.ArtifactStorageUnavailableException;
import com.huawei.skillcenter.distribution.DistributionAuthorizationException;
import com.huawei.skillcenter.events.InvocationEventConflictException;
import com.huawei.skillcenter.analytics.InvalidAnalyticsQueryException;
import com.huawei.skillcenter.governance.ForbiddenException;
import com.huawei.skillcenter.governance.ConfigConflictException;
import com.huawei.skillcenter.governance.CollectionNotFoundException;
import com.huawei.skillcenter.governance.InvalidLifecycleRequestException;
import com.huawei.skillcenter.governance.InstallationNotFoundException;
import com.huawei.skillcenter.governance.ReviewStateConflictException;
import com.huawei.skillcenter.governance.QualityGateBlockedException;
import com.huawei.skillcenter.release.ReleaseConflictException;
import com.huawei.skillcenter.release.ReleaseInvalidStateException;
import com.huawei.skillcenter.release.ReleaseNotFoundException;
import com.huawei.skillcenter.release.ReleasePersistenceException;
import com.huawei.skillcenter.release.ReleaseTargetException;
import com.huawei.skillcenter.release.ReleaseAdmissionException;
import com.huawei.skillcenter.relationship.SkillRelationConflictException;
import com.huawei.skillcenter.relationship.SkillRelationCycleException;
import com.huawei.skillcenter.relationship.SkillRelationLimitException;
import com.huawei.skillcenter.relationship.SkillRelationNotFoundException;
import com.huawei.skillcenter.relationship.SkillRelationPersistenceException;
import com.huawei.skillcenter.relationship.SkillRelationVersionNotFoundException;
import com.huawei.skillcenter.quality.QualityEvidenceStore;
import com.huawei.skillcenter.governance.SkillVersionNotFoundException;
import com.huawei.skillcenter.governance.SkillVersionConflictException;
import com.huawei.skillcenter.governance.VersionStateConflictException;
import com.huawei.skillcenter.distribution.WithdrawnVersionUnavailableException;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.GovernanceStateConflictException;
import com.huawei.skillcenter.governance.ExportException;
import com.huawei.skillcenter.governance.RetentionException;
import com.huawei.skillcenter.skill.SkillNotFoundException;
import com.huawei.skillcenter.security.IdempotencyException;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import com.huawei.skillcenter.operations.InvalidOperationsQueryException;
import com.huawei.skillcenter.operations.MetricsTokenException;
import com.huawei.skillcenter.operations.InvalidOperationsAlertQueryException;
import com.huawei.skillcenter.operations.RuntimeSummaryConflictException;
import com.huawei.skillcenter.operations.RuntimeSummaryStore;
import com.huawei.skillcenter.operations.OperationsAlertStatePersistenceException;
import com.huawei.skillcenter.operations.ProductionEvidenceConflictException;
import com.huawei.skillcenter.operations.ProductionEvidencePersistenceException;
import com.huawei.skillcenter.persistence.PersistenceControlException;
import com.huawei.skillcenter.quality.QualityRunNotFoundException;
import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionSourceInvalidException;
import com.huawei.skillcenter.lifecycle.SkillLifecycleProjectionQueryInvalidException;
import com.huawei.skillcenter.search.SkillSearchIndexControlException;
import com.huawei.skillcenter.search.SkillSearchIndexPersistenceException;
import com.huawei.skillcenter.quality.EvaluationSuiteVersionConflictException;
import com.huawei.skillcenter.quality.EvaluationSuiteVersionNotFoundException;
import com.huawei.skillcenter.quality.EvaluationSuiteNotEnabledException;
import com.huawei.skillcenter.quality.CompatibilityMatrixConflictException;
import com.huawei.skillcenter.quality.CompatibilityMatrixNotFoundException;
import com.huawei.skillcenter.quality.ProviderUnavailableException;
import com.huawei.skillcenter.quality.RunnerScenarioNotAllowedException;
import com.huawei.skillcenter.quality.RunnerVersionNotAllowedException;
import com.huawei.skillcenter.quality.SkillExecutionNotFoundException;
import com.huawei.skillcenter.quality.SkillExecutionStore;
import com.huawei.skillcenter.quality.OptimizationSuggestionNotFoundException;
import com.huawei.skillcenter.quality.OptimizationSuggestionDispositionStore;
import com.huawei.skillcenter.quality.OptimizationSuggestionThresholdsStore;
import com.huawei.skillcenter.quality.BenchmarkStore;
import com.huawei.skillcenter.quality.OptimizationWorkItemConflictException;
import com.huawei.skillcenter.quality.OptimizationWorkItemEvidenceException;
import com.huawei.skillcenter.quality.OptimizationWorkItemInvalidStateException;
import com.huawei.skillcenter.quality.OptimizationWorkItemNotFoundException;
import com.huawei.skillcenter.quality.OptimizationWorkItemStore;
import com.huawei.skillcenter.quality.OptimizationExperimentConflictException;
import com.huawei.skillcenter.quality.OptimizationExperimentInvalidStateException;
import com.huawei.skillcenter.quality.OptimizationExperimentNotFoundException;
import com.huawei.skillcenter.quality.OptimizationExperimentPersistenceException;
import com.huawei.skillcenter.quality.OptimizationExperimentDecisionInvalidStateException;
import com.huawei.skillcenter.quality.OptimizationExperimentDecisionNotFoundException;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentNotFoundException;
import com.huawei.skillcenter.quality.OptimizationExperimentAssessmentStore;
import com.huawei.skillcenter.execution.ExecutionEnvironmentConflictException;
import com.huawei.skillcenter.execution.ExecutionEnvironmentNotFoundException;
import com.huawei.skillcenter.execution.ExecutionEnvironmentRevisionConflictException;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStateConflictException;
import com.huawei.skillcenter.execution.ExecutionEnvironmentStore;
import com.huawei.skillcenter.governance.OrganizationDirectoryRevisionConflictException;
import com.huawei.skillcenter.governance.OrganizationDirectoryUnavailableException;
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

    @ExceptionHandler(SkillScopeNotFoundException.class)
    ResponseEntity<ErrorEnvelope> skillScopeNotFound(SkillScopeNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SKILL_SCOPE_NOT_FOUND", "Skill scope was not found", request, List.of());
    }

    @ExceptionHandler(SkillNotVisibleException.class)
    ResponseEntity<ErrorEnvelope> skillNotVisible(SkillNotVisibleException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SKILL_NOT_VISIBLE", "Skill not found or not visible", request, List.of());
    }

    @ExceptionHandler(SkillManageForbiddenException.class)
    ResponseEntity<ErrorEnvelope> skillManageForbidden(SkillManageForbiddenException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "SKILL_MANAGE_FORBIDDEN",
                "Actor does not have permission to manage this Skill", request, List.of());
    }

    @ExceptionHandler(SkillScopeInvalidException.class)
    ResponseEntity<ErrorEnvelope> skillScopeInvalid(SkillScopeInvalidException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "SKILL_SCOPE_INVALID", "Skill scope request is invalid", request, List.of());
    }

    @ExceptionHandler(SkillScopeConflictException.class)
    ResponseEntity<ErrorEnvelope> skillScopeConflict(SkillScopeConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SKILL_SCOPE_CONFLICT", "Skill scope revision conflict", request, List.of());
    }

    @ExceptionHandler(SkillScopePersistenceException.class)
    ResponseEntity<ErrorEnvelope> skillScopePersistence(SkillScopePersistenceException exception,
                                                        HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SKILL_SCOPE_PERSISTENCE_FAILED",
                "Skill scope state is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(QualityRunNotFoundException.class)
    ResponseEntity<ErrorEnvelope> qualityRunNotFound(QualityRunNotFoundException exception,
                                                       HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "QUALITY_RUN_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(EvaluationSuiteVersionConflictException.class)
    ResponseEntity<ErrorEnvelope> evaluationSuiteVersionConflict(EvaluationSuiteVersionConflictException exception,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "QUALITY_SUITE_VERSION_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(EvaluationSuiteVersionNotFoundException.class)
    ResponseEntity<ErrorEnvelope> evaluationSuiteVersionNotFound(EvaluationSuiteVersionNotFoundException exception,
                                                                   HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "QUALITY_SUITE_VERSION_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(EvaluationSuiteNotEnabledException.class)
    ResponseEntity<ErrorEnvelope> evaluationSuiteNotEnabled(EvaluationSuiteNotEnabledException exception,
                                                              HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "QUALITY_SUITE_NOT_ENABLED", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(CompatibilityMatrixNotFoundException.class)
    ResponseEntity<ErrorEnvelope> compatibilityMatrixNotFound(CompatibilityMatrixNotFoundException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "COMPATIBILITY_MATRIX_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(CompatibilityMatrixConflictException.class)
    ResponseEntity<ErrorEnvelope> compatibilityMatrixConflict(CompatibilityMatrixConflictException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "COMPATIBILITY_MATRIX_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    ResponseEntity<ErrorEnvelope> providerUnavailable(ProviderUnavailableException exception,
                                                       HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.code(),
                "External provider is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(ArtifactStorageUnavailableException.class)
    ResponseEntity<ErrorEnvelope> artifactStorageUnavailable(ArtifactStorageUnavailableException exception,
                                                              HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.code(),
                "Artifact storage is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(RunnerVersionNotAllowedException.class)
    ResponseEntity<ErrorEnvelope> runnerVersionNotAllowed(RunnerVersionNotAllowedException exception,
                                                            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RUNNER_VERSION_NOT_ALLOWED",
                "Runner 只能执行已发布的 Skill 版本", request, List.of());
    }

    @ExceptionHandler(RunnerScenarioNotAllowedException.class)
    ResponseEntity<ErrorEnvelope> runnerScenarioNotAllowed(RunnerScenarioNotAllowedException exception,
                                                             HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "RUNNER_SCENARIO_INVALID",
                "Runner 场景不在允许范围内", request, List.of());
    }

    @ExceptionHandler(SkillExecutionNotFoundException.class)
    ResponseEntity<ErrorEnvelope> skillExecutionNotFound(SkillExecutionNotFoundException exception,
                                                           HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RUNNER_EXECUTION_NOT_FOUND", "Runner 执行记录不存在", request, List.of());
    }

    @ExceptionHandler(SkillExecutionStore.SkillExecutionPersistenceException.class)
    ResponseEntity<ErrorEnvelope> skillExecutionPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNNER_EXECUTION_PERSISTENCE_FAILED",
                "Runner 执行记录暂时不可用", request, List.of());
    }

    @ExceptionHandler(SkillExecutionStore.SkillExecutionConflictException.class)
    ResponseEntity<ErrorEnvelope> skillExecutionConflict(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RUNNER_EXECUTION_CONFLICT",
                "Runner 执行记录已存在且内容不一致", request, List.of());
    }

    @ExceptionHandler(OptimizationSuggestionNotFoundException.class)
    ResponseEntity<ErrorEnvelope> optimizationSuggestionNotFound(OptimizationSuggestionNotFoundException exception,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "OPTIMIZATION_SUGGESTION_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationSuggestionDispositionStore.OptimizationSuggestionDispositionPersistenceException.class)
    ResponseEntity<ErrorEnvelope> optimizationSuggestionPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPTIMIZATION_DISPOSITION_PERSISTENCE_FAILED",
                "Optimization suggestion disposition is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(OptimizationSuggestionThresholdsStore.OptimizationSuggestionThresholdsPersistenceException.class)
    ResponseEntity<ErrorEnvelope> optimizationThresholdPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPTIMIZATION_THRESHOLDS_PERSISTENCE_FAILED",
                "Optimization suggestion thresholds are temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(OptimizationWorkItemNotFoundException.class)
    ResponseEntity<ErrorEnvelope> optimizationWorkItemNotFound(OptimizationWorkItemNotFoundException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "OPTIMIZATION_WORK_ITEM_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationWorkItemConflictException.class)
    ResponseEntity<ErrorEnvelope> optimizationWorkItemConflict(OptimizationWorkItemConflictException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OPTIMIZATION_WORK_ITEM_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationWorkItemInvalidStateException.class)
    ResponseEntity<ErrorEnvelope> optimizationWorkItemState(OptimizationWorkItemInvalidStateException exception,
                                                             HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OPTIMIZATION_WORK_ITEM_INVALID_STATE", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationWorkItemEvidenceException.class)
    ResponseEntity<ErrorEnvelope> optimizationWorkItemEvidence(OptimizationWorkItemEvidenceException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "OPTIMIZATION_WORK_ITEM_EVIDENCE_INVALID", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationWorkItemStore.OptimizationWorkItemPersistenceException.class)
    ResponseEntity<ErrorEnvelope> optimizationWorkItemPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPTIMIZATION_WORK_ITEM_PERSISTENCE_FAILED",
                "Optimization work item storage is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(OptimizationExperimentNotFoundException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentNotFound(OptimizationExperimentNotFoundException exception,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "OPTIMIZATION_EXPERIMENT_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationExperimentConflictException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentConflict(OptimizationExperimentConflictException exception,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OPTIMIZATION_EXPERIMENT_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationExperimentInvalidStateException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentState(OptimizationExperimentInvalidStateException exception,
                                                               HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OPTIMIZATION_EXPERIMENT_INVALID_STATE", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationExperimentPersistenceException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPTIMIZATION_EXPERIMENT_PERSISTENCE_FAILED",
                "Optimization experiment storage is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(OptimizationExperimentAssessmentNotFoundException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentAssessmentNotFound(
            OptimizationExperimentAssessmentNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "OPTIMIZATION_EXPERIMENT_ASSESSMENT_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationExperimentAssessmentStore.OptimizationExperimentAssessmentPersistenceException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentAssessmentPersistence(Exception exception,
                                                                               HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPTIMIZATION_EXPERIMENT_ASSESSMENT_PERSISTENCE_FAILED",
                "Optimization experiment assessment storage is temporarily unavailable", request, List.of());
    }


    @ExceptionHandler(OptimizationExperimentDecisionNotFoundException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentDecisionNotFound(
            OptimizationExperimentDecisionNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "OPTIMIZATION_EXPERIMENT_DECISION_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimizationExperimentDecisionInvalidStateException.class)
    ResponseEntity<ErrorEnvelope> optimizationExperimentDecisionState(
            OptimizationExperimentDecisionInvalidStateException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OPTIMIZATION_EXPERIMENT_DECISION_INVALID_STATE", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExecutionEnvironmentNotFoundException.class)
    ResponseEntity<ErrorEnvelope> executionEnvironmentNotFound(ExecutionEnvironmentNotFoundException exception,
                                                               HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "EXECUTION_ENVIRONMENT_NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExecutionEnvironmentConflictException.class)
    ResponseEntity<ErrorEnvelope> executionEnvironmentConflict(ExecutionEnvironmentConflictException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "EXECUTION_ENVIRONMENT_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExecutionEnvironmentStateConflictException.class)
    ResponseEntity<ErrorEnvelope> executionEnvironmentState(ExecutionEnvironmentStateConflictException exception,
                                                             HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "EXECUTION_ENVIRONMENT_NOT_ACTIVE", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExecutionEnvironmentRevisionConflictException.class)
    ResponseEntity<ErrorEnvelope> executionEnvironmentRevisionConflict(
            ExecutionEnvironmentRevisionConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "EXECUTION_ENVIRONMENT_REVISION_CONFLICT",
                exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExecutionEnvironmentStore.ExecutionEnvironmentPersistenceException.class)
    ResponseEntity<ErrorEnvelope> executionEnvironmentPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_ENVIRONMENT_PERSISTENCE_FAILED",
                "Execution environment catalog is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(BenchmarkStore.BenchmarkPersistenceException.class)
    ResponseEntity<ErrorEnvelope> benchmarkPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "BENCHMARK_PERSISTENCE_FAILED",
                "Benchmark evidence is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(RuntimeSummaryStore.RuntimeSummaryPersistenceException.class)
    ResponseEntity<ErrorEnvelope> runtimeSummaryPersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_SUMMARY_PERSISTENCE_FAILED",
                "Runtime summary storage is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(OperationsAlertStatePersistenceException.class)
    ResponseEntity<ErrorEnvelope> operationsAlertStatePersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPERATIONS_ALERT_STATE_PERSISTENCE_FAILED",
                "Operations alert state storage is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(QualityEvidenceStore.QualityEvidencePersistenceException.class)
    ResponseEntity<ErrorEnvelope> qualityEvidencePersistence(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "QUALITY_EVIDENCE_PERSISTENCE_FAILED",
                "Quality evidence storage is temporarily unavailable", request, List.of());
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

    @ExceptionHandler(SkillVersionConflictException.class)
    ResponseEntity<ErrorEnvelope> skillVersionConflict(SkillVersionConflictException exception,
                                                        HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SKILL_VERSION_CONFLICT",
                "Skill version already exists or is not newer than the latest version", request, List.of());
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

    @ExceptionHandler(RuntimeSummaryConflictException.class)
    ResponseEntity<ErrorEnvelope> runtimeSummaryConflict(RuntimeSummaryConflictException exception,
                                                           HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "EVENT_ID_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ProductionEvidenceConflictException.class)
    ResponseEntity<ErrorEnvelope> productionEvidenceConflict(ProductionEvidenceConflictException exception,
                                                               HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "PRODUCTION_EVIDENCE_CONFLICT",
                "Production handoff evidence was changed; reload and retry", request, List.of());
    }

    @ExceptionHandler(ProductionEvidencePersistenceException.class)
    ResponseEntity<ErrorEnvelope> productionEvidencePersistence(ProductionEvidencePersistenceException exception,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCTION_EVIDENCE_PERSISTENCE_FAILED",
                "Production handoff evidence is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(GovernanceStateConflictException.class)
    ResponseEntity<ErrorEnvelope> governanceStateConflict(GovernanceStateConflictException exception,
                                                            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "GOVERNANCE_STATE_CONFLICT",
                "Governance state was changed; reload and retry", request, List.of());
    }

    @ExceptionHandler(PackageValidationException.class)
    ResponseEntity<ErrorEnvelope> packageValidation(PackageValidationException exception, HttpServletRequest request) {
        List<ApiError.ErrorDetail> details = exception.result().errors().stream()
                .map(validationError -> new ApiError.ErrorDetail(validationError.path(), validationError.reason()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "PACKAGE_VALIDATION_FAILED", "Skill package validation failed", request, details);
    }

    @ExceptionHandler(ResumablePackageUploadException.class)
    ResponseEntity<ErrorEnvelope> resumableUpload(ResumablePackageUploadException exception,
                                                   HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(SkillLifecycleProjectionSourceInvalidException.class)
    ResponseEntity<ErrorEnvelope> lifecycleProjectionSourceInvalid(SkillLifecycleProjectionSourceInvalidException exception,
                                                                     HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.code(),
                "Skill lifecycle projection source is invalid", request, List.of());
    }

    @ExceptionHandler(SkillLifecycleProjectionQueryInvalidException.class)
    ResponseEntity<ErrorEnvelope> lifecycleProjectionQueryInvalid(SkillLifecycleProjectionQueryInvalidException exception,
                                                                    HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(SkillSearchIndexControlException.class)
    ResponseEntity<ErrorEnvelope> searchIndexControl(SkillSearchIndexControlException exception,
                                                       HttpServletRequest request) {
        String code = exception.code();
        HttpStatus status = switch (code) {
            case "SEARCH_INDEX_SOURCE_CONFLICT" -> HttpStatus.CONFLICT;
            case "SEARCH_INDEX_REBUILD_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        String message = switch (code) {
            case "SEARCH_INDEX_SOURCE_CONFLICT" -> "Search index source changed; retry the rebuild";
            case "SEARCH_INDEX_REBUILD_FAILED" -> "Search index rebuild is temporarily unavailable";
            case "SEARCH_INDEX_REQUEST_ID_REQUIRED" -> "Request ID is required";
            default -> "Search index request is invalid";
        };
        return error(status, code, message, request, List.of());
    }

    @ExceptionHandler(SkillSearchIndexPersistenceException.class)
    ResponseEntity<ErrorEnvelope> searchIndexPersistence(SkillSearchIndexPersistenceException exception,
                                                          HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_INDEX_PERSISTENCE_UNAVAILABLE",
                "Search index is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ErrorEnvelope> forbidden(ForbiddenException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(PersistenceControlException.class)
    ResponseEntity<ErrorEnvelope> persistenceControl(PersistenceControlException exception,
                                                      HttpServletRequest request) {
        String code = safePersistenceCode(exception.getCode());
        HttpStatus status = switch (code) {
            case "PERSISTENCE_SNAPSHOT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "PERSISTENCE_SNAPSHOT_INVALID" -> HttpStatus.BAD_REQUEST;
            case "PERSISTENCE_RESTORE_BLOCKED",
                    "PERSISTENCE_ARTIFACT_MISSING",
                    "PERSISTENCE_ARTIFACT_CORRUPTED",
                    "PERSISTENCE_NOT_READY",
                    "PERSISTENCE_MIGRATION_REQUIRED",
                    "PERSISTENCE_MIGRATION_UNSUPPORTED" -> HttpStatus.CONFLICT;
            case "PERSISTENCE_MIGRATION_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String message = switch (status) {
            case NOT_FOUND -> "Persistence snapshot was not found";
            case BAD_REQUEST -> "Persistence control request is invalid";
            case CONFLICT -> "Persistence control operation is blocked";
            default -> "Persistence control plane is temporarily unavailable";
        };
        return error(status, code, message, request, List.of());
    }

    private String safePersistenceCode(String code) {
        Set<String> allowed = Set.of(
                "PERSISTENCE_SNAPSHOT_NOT_FOUND",
                "PERSISTENCE_SNAPSHOT_INVALID",
                "PERSISTENCE_RESTORE_BLOCKED",
                "PERSISTENCE_ARTIFACT_MISSING",
                "PERSISTENCE_ARTIFACT_CORRUPTED",
                "PERSISTENCE_NOT_READY",
                "PERSISTENCE_MIGRATION_REQUIRED",
                "PERSISTENCE_MIGRATION_UNSUPPORTED",
                "PERSISTENCE_MIGRATION_FAILED");
        return allowed.contains(code) ? code : "PERSISTENCE_CONTROL_UNAVAILABLE";
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

    @ExceptionHandler(QualityGateBlockedException.class)
    ResponseEntity<ErrorEnvelope> qualityGateBlocked(QualityGateBlockedException exception,
                                                      HttpServletRequest request) {
        List<ApiError.ErrorDetail> details = exception.reasons().stream()
                .map(reason -> new ApiError.ErrorDetail("qualityGate", reason))
                .toList();
        return error(HttpStatus.CONFLICT, "QUALITY_GATE_BLOCKED",
                "Quality gate must pass before publishing this Skill version", request, details);
    }

    @ExceptionHandler(ReleaseNotFoundException.class)
    ResponseEntity<ErrorEnvelope> releaseNotFound(ReleaseNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Release record not found", request, List.of());
    }

    @ExceptionHandler(ReleaseConflictException.class)
    ResponseEntity<ErrorEnvelope> releaseConflict(ReleaseConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RELEASE_CONFLICT", "Release request conflicts with an existing release", request, List.of());
    }

    @ExceptionHandler(ReleaseInvalidStateException.class)
    ResponseEntity<ErrorEnvelope> releaseInvalidState(ReleaseInvalidStateException exception,
                                                       HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RELEASE_INVALID_STATE", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ReleaseTargetException.class)
    ResponseEntity<ErrorEnvelope> releaseTarget(ReleaseTargetException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_GATEWAY, exception.reasonCode(),
                "Release target execution failed", request, List.of());
    }

    @ExceptionHandler(ReleasePersistenceException.class)
    ResponseEntity<ErrorEnvelope> releasePersistence(ReleasePersistenceException exception,
                                                      HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RELEASE_PERSISTENCE_FAILED",
                "Release state is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(ReleaseAdmissionException.class)
    ResponseEntity<ErrorEnvelope> releaseAdmission(ReleaseAdmissionException exception,
                                                    HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.reasonCode(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(SkillRelationCycleException.class)
    ResponseEntity<ErrorEnvelope> skillRelationCycle(SkillRelationCycleException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SKILL_RELATION_CYCLE", "Skill relation graph contains a cycle", request, List.of());
    }

    @ExceptionHandler(SkillRelationConflictException.class)
    ResponseEntity<ErrorEnvelope> skillRelationConflict(SkillRelationConflictException exception,
                                                         HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SKILL_RELATION_CONFLICT", "Skill relation conflicts with existing state", request, List.of());
    }

    @ExceptionHandler(SkillRelationVersionNotFoundException.class)
    ResponseEntity<ErrorEnvelope> skillRelationVersionNotFound(SkillRelationVersionNotFoundException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SKILL_RELATION_VERSION_NOT_FOUND", "Skill relation version was not found", request, List.of());
    }

    @ExceptionHandler(SkillRelationNotFoundException.class)
    ResponseEntity<ErrorEnvelope> skillRelationNotFound(SkillRelationNotFoundException exception,
                                                        HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "SKILL_RELATION_NOT_FOUND", "Skill relation was not found", request, List.of());
    }

    @ExceptionHandler(SkillRelationLimitException.class)
    ResponseEntity<ErrorEnvelope> skillRelationLimit(SkillRelationLimitException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "SKILL_RELATION_LIMIT_INVALID", "Skill relation impact limits are invalid", request, List.of());
    }

    @ExceptionHandler(SkillRelationPersistenceException.class)
    ResponseEntity<ErrorEnvelope> skillRelationPersistence(SkillRelationPersistenceException exception,
                                                           HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SKILL_RELATION_PERSISTENCE_FAILED",
                "Skill relation state is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(GovernanceStore.GovernancePersistenceException.class)
    ResponseEntity<ErrorEnvelope> governancePersistence(GovernanceStore.GovernancePersistenceException exception,
                                                         HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "GOVERNANCE_PERSISTENCE_FAILED",
                "Governance state is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(OrganizationDirectoryUnavailableException.class)
    ResponseEntity<ErrorEnvelope> organizationDirectoryUnavailable(
            OrganizationDirectoryUnavailableException exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.reasonCode(),
                "Organization directory is temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(OrganizationDirectoryRevisionConflictException.class)
    ResponseEntity<ErrorEnvelope> organizationDirectoryRevisionConflict(
            OrganizationDirectoryRevisionConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "DIRECTORY_REVISION_CONFLICT",
                "Organization directory revision conflict", request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorEnvelope> unreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        if (containsRunnerScenarioError(exception)) {
            return error(HttpStatus.BAD_REQUEST, "RUNNER_SCENARIO_INVALID",
                    "Runner 场景不在允许范围内", request, List.of());
        }
        return error(HttpStatus.BAD_REQUEST, "EVENT_SCHEMA_INVALID", "Request body does not match the allowed event schema", request, List.of());
    }

    private boolean containsRunnerScenarioError(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof com.huawei.skillcenter.quality.RunnerScenarioNotAllowedException) return true;
            current = current.getCause();
        }
        return false;
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
