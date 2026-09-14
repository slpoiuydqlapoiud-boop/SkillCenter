package com.huawei.skillcenter.quality;

import com.huawei.skillcenter.execution.ExecutionEnvironmentKind;
import com.huawei.skillcenter.execution.ExecutionEnvironmentService;
import com.huawei.skillcenter.execution.ExecutionEnvironmentSnapshot;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class QualityEvaluationService {
    private final SkillRunner runner;
    private final EvaluationProvider evaluationProvider;
    private final ObservabilityProvider observabilityProvider;
    private final StaticQualityEvaluator staticQualityEvaluator;
    private final QualityGateService qualityGateService;
    private final ProviderRetryPolicy retryPolicy;
    private final QualityEvidenceRepository evidenceRepository;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final Clock clock;
    private final ConcurrentHashMap<String, EvaluationSuite> suites = new ConcurrentHashMap<>(defaultSuites());
    private volatile QualityRuleSet activeRules = new QualityRuleSet("default", "quality-v1", 80, 0.8, 90);
    private final ConcurrentHashMap<String, EvaluationRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QualitySnapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<EvaluationCaseResult>> caseResults = new ConcurrentHashMap<>();
    private final Set<String> cancellationRequests = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> activeWorkers = ConcurrentHashMap.newKeySet();

    @Autowired
    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    ObservabilityProvider observabilityProvider,
                                    StaticQualityEvaluator staticQualityEvaluator,
                                    QualityGateService qualityGateService,
                                    QualityEvidenceRepository evidenceRepository,
                                    ExecutionEnvironmentService executionEnvironmentService) {
        this(runner, evaluationProvider, observabilityProvider, staticQualityEvaluator, qualityGateService,
                Clock.systemUTC(), ProviderRetryPolicy.defaultPolicy(), evidenceRepository, executionEnvironmentService);
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider, Clock clock) {
        this(runner, evaluationProvider, new NoopObservabilityProvider(), new StaticQualityEvaluator(),
                new QualityGateService(), clock, ProviderRetryPolicy.defaultPolicy(), new QualityEvidenceStore());
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    ObservabilityProvider observabilityProvider, Clock clock) {
        this(runner, evaluationProvider, observabilityProvider, new StaticQualityEvaluator(),
                new QualityGateService(), clock, ProviderRetryPolicy.defaultPolicy(), new QualityEvidenceStore());
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    ProviderRetryPolicy retryPolicy, Clock clock) {
        this(runner, evaluationProvider, new NoopObservabilityProvider(), new StaticQualityEvaluator(),
                new QualityGateService(), clock, retryPolicy, new QualityEvidenceStore());
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    ObservabilityProvider observabilityProvider,
                                    StaticQualityEvaluator staticQualityEvaluator,
                                    QualityGateService qualityGateService, Clock clock) {
        this(runner, evaluationProvider, observabilityProvider, staticQualityEvaluator, qualityGateService,
                clock, ProviderRetryPolicy.defaultPolicy(), new QualityEvidenceStore());
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    ObservabilityProvider observabilityProvider,
                                    StaticQualityEvaluator staticQualityEvaluator,
                                    QualityGateService qualityGateService, Clock clock,
                                    ProviderRetryPolicy retryPolicy) {
        this(runner, evaluationProvider, observabilityProvider, staticQualityEvaluator, qualityGateService,
                clock, retryPolicy, new QualityEvidenceStore());
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    Clock clock, QualityEvidenceRepository evidenceRepository) {
        this(runner, evaluationProvider, new NoopObservabilityProvider(), new StaticQualityEvaluator(),
                new QualityGateService(), clock, ProviderRetryPolicy.defaultPolicy(), evidenceRepository, null);
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    Clock clock, QualityEvidenceRepository evidenceRepository,
                                    ExecutionEnvironmentService executionEnvironmentService) {
        this(runner, evaluationProvider, new NoopObservabilityProvider(), new StaticQualityEvaluator(),
                new QualityGateService(), clock, ProviderRetryPolicy.defaultPolicy(), evidenceRepository,
                executionEnvironmentService);
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    ObservabilityProvider observabilityProvider,
                                    StaticQualityEvaluator staticQualityEvaluator,
                                    QualityGateService qualityGateService, Clock clock,
                                    ProviderRetryPolicy retryPolicy,
                                    QualityEvidenceRepository evidenceRepository) {
        this(runner, evaluationProvider, observabilityProvider, staticQualityEvaluator, qualityGateService,
                clock, retryPolicy, evidenceRepository, null);
    }

    public QualityEvaluationService(SkillRunner runner, EvaluationProvider evaluationProvider,
                                    ObservabilityProvider observabilityProvider,
                                    StaticQualityEvaluator staticQualityEvaluator,
                                    QualityGateService qualityGateService, Clock clock,
                                    ProviderRetryPolicy retryPolicy,
                                    QualityEvidenceRepository evidenceRepository,
                                    ExecutionEnvironmentService executionEnvironmentService) {
        this.runner = runner;
        this.evaluationProvider = evaluationProvider;
        this.observabilityProvider = observabilityProvider;
        this.staticQualityEvaluator = staticQualityEvaluator;
        this.qualityGateService = qualityGateService;
        this.clock = clock;
        this.retryPolicy = retryPolicy == null ? ProviderRetryPolicy.defaultPolicy() : retryPolicy;
        this.evidenceRepository = evidenceRepository == null ? new QualityEvidenceStore() : evidenceRepository;
        this.executionEnvironmentService = executionEnvironmentService;
        restoreEvidence(this.evidenceRepository.load());
    }

    public synchronized EvaluationRun submit(EvaluationRequest request) {
        require(request.skillId(), "skillId");
        require(request.skillVersion(), "skillVersion");
        require(request.suiteId(), "suiteId");
        if (request.timeoutMs() <= 0 || request.timeoutMs() > 120_000) {
            throw new IllegalArgumentException("timeoutMs must be between 1 and 120000");
        }
        String scenario = request.scenario() == null || request.scenario().isBlank()
                ? "success" : request.scenario().trim().toLowerCase();
        if (!List.of("success", "failure", "timeout", "cancel", "cancelled").contains(scenario)) {
            throw new IllegalArgumentException("scenario is not supported");
        }
        EvaluationSuite suite = resolveSuite(request.suiteId(), request.suiteVersion());
        if (!suite.enabled()) {
            throw new EvaluationSuiteNotEnabledException("suite version is disabled: " + request.suiteId()
                    + "@" + suite.version());
        }
        EnvironmentSnapshots environments = validateExecutionEnvironments(request);
        if (!request.experimentId().isBlank()) {
            EvaluationRun existing = findByExperimentId(request.experimentId());
            if (existing != null) {
                ensureSameExperimentContext(existing, request, suite);
                return existing;
            }
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        EvaluationRun queued = new EvaluationRun(id, request.skillId(), request.skillVersion(), suite.id(), suite.version(),
                EvaluationRunStatus.QUEUED, runner.providerId(), evaluationProvider.providerId(), runnerDataSource(), now, null,
                suite.cases().size(), 0, 0, "", QualityGateStatus.BLOCKED, List.of("EVALUATION_NOT_COMPLETED"),
                request.runtimeId(), request.mcpServerId(), request.llmProviderId(), request.experimentId(),
                environments.runtime(), environments.mcpServer(), environments.llmProvider());
        runs.put(id, queued);
        persistEvidence();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        activeWorkers.add(completion);
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    execute(id, request, suite);
                } catch (RuntimeException exception) {
                    markFailed(id, exception);
                } finally {
                    activeWorkers.remove(completion);
                    completion.complete(null);
                }
            });
        } catch (RuntimeException exception) {
            activeWorkers.remove(completion);
            completion.completeExceptionally(exception);
            throw exception;
        }
        return queued;
    }

    /**
     * Waits for already-started evaluation workers to finish without changing cancellation semantics.
     * This is used by lifecycle shutdown and deterministic test cleanup; it never starts new work.
     */
    boolean awaitQuiescence(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            CompletableFuture<?>[] pending = activeWorkers.toArray(CompletableFuture[]::new);
            if (pending.length == 0) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                CompletableFuture.allOf(pending).get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                return false;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException exception) {
                // A worker's failure is already represented by its stable evaluation state.
            }
        }
    }

    @PreDestroy
    void stopWorkers() {
        awaitQuiescence(Duration.ofSeconds(5));
    }

    private EnvironmentSnapshots validateExecutionEnvironments(EvaluationRequest request) {
        if (executionEnvironmentService == null) {
            return new EnvironmentSnapshots(
                    snapshot(ExecutionEnvironmentKind.AGENT_RUNTIME, request.runtimeId()),
                    snapshot(ExecutionEnvironmentKind.MCP_SERVER, request.mcpServerId()),
                    snapshot(ExecutionEnvironmentKind.LLM_PROVIDER, request.llmProviderId()));
        }
        return new EnvironmentSnapshots(
                executionEnvironmentService.requireActiveSnapshot(ExecutionEnvironmentKind.AGENT_RUNTIME, request.runtimeId()),
                executionEnvironmentService.requireActiveSnapshot(ExecutionEnvironmentKind.MCP_SERVER, request.mcpServerId()),
                executionEnvironmentService.requireActiveSnapshot(ExecutionEnvironmentKind.LLM_PROVIDER, request.llmProviderId()));
    }

    private ExecutionEnvironmentSnapshot snapshot(ExecutionEnvironmentKind kind, String environmentId) {
        return environmentId == null || environmentId.isBlank()
                ? ExecutionEnvironmentSnapshot.empty(kind)
                : ExecutionEnvironmentSnapshot.legacy(kind, environmentId);
    }

    private record EnvironmentSnapshots(ExecutionEnvironmentSnapshot runtime,
                                        ExecutionEnvironmentSnapshot mcpServer,
                                        ExecutionEnvironmentSnapshot llmProvider) {
    }

    public synchronized EvaluationRun find(String id) {
        EvaluationRun run = runs.get(id);
        if (run == null) {
            throw new QualityRunNotFoundException(id);
        }
        return run;
    }

    public synchronized EvaluationRun findByExperimentId(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) return null;
        return runs.values().stream()
                .filter(run -> experimentId.trim().equals(run.experimentId()))
                .findFirst()
                .orElse(null);
    }

    private void ensureSameExperimentContext(EvaluationRun existing, EvaluationRequest request,
                                             EvaluationSuite suite) {
        if (!existing.skillId().equals(request.skillId())
                || !existing.skillVersion().equals(request.skillVersion())
                || !existing.suiteId().equals(suite.id())
                || !existing.suiteVersion().equals(suite.version())
                || !existing.runtimeId().equals(request.runtimeId())
                || !existing.mcpServerId().equals(request.mcpServerId())
                || !existing.llmProviderId().equals(request.llmProviderId())) {
            throw new IllegalArgumentException("experimentId is already bound to a different evaluation context");
        }
    }

    public EvaluationRun awaitTerminal(String id, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        EvaluationRun current;
        do {
            current = find(id);
            if (isTerminal(current.status())) return current;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return current;
            }
        } while (System.nanoTime() < deadline);
        return find(id);
    }

    /**
     * Requests cooperative cancellation for a queued or running evaluation.
     * A terminal evaluation is returned unchanged so retries are idempotent.
     */
    public synchronized EvaluationRun cancel(String id) {
        EvaluationRun current = find(id);
        if (isTerminal(current.status())) {
            return current;
        }
        cancellationRequests.add(id);
        EvaluationRun cancelled = cancelled(current);
        runs.put(id, cancelled);
        persistEvidence();
        return cancelled;
    }

    public List<EvaluationRun> list(String skillId) {
        return list(skillId, null, null, null, null);
    }

    public List<EvaluationRun> list(String skillId, String dataSource, String runtimeId,
                                    String mcpServerId, String llmProviderId) {
        dataSource = normalizeDataSource(dataSource);
        runtimeId = normalizeEnvironmentFilter(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironmentFilter(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironmentFilter(llmProviderId, "llmProviderId");
        String requestedDataSource = dataSource;
        String requestedRuntimeId = runtimeId;
        String requestedMcpServerId = mcpServerId;
        String requestedLlmProviderId = llmProviderId;
        return runs.values().stream()
                .filter(run -> skillId == null || skillId.isBlank() || skillId.equals(run.skillId()))
                .filter(run -> requestedDataSource == null || requestedDataSource.equals(run.dataSource()))
                .filter(run -> matchesEnvironment(run.runtimeId(), requestedRuntimeId)
                        && matchesEnvironment(run.mcpServerId(), requestedMcpServerId)
                        && matchesEnvironment(run.llmProviderId(), requestedLlmProviderId))
                .sorted(Comparator.comparing(EvaluationRun::createdAt).reversed())
                .toList();
    }

    public List<EvaluationSuite> listSuites() {
        return suites.values().stream()
                .sorted(Comparator.comparing(EvaluationSuite::id).thenComparing(EvaluationSuite::version))
                .toList();
    }

    public synchronized EvaluationSuite createSuite(EvaluationSuiteRequest request) {
        EvaluationSuite suite = new EvaluationSuite(request.id(), request.name(), request.version(),
                request.enabled(), request.cases());
        String key = suiteKey(suite.id(), suite.version());
        if (suites.containsKey(key)) {
            throw new EvaluationSuiteVersionConflictException("suite version already exists: " + key);
        }
        if (suite.enabled()) {
            suites.replaceAll((existingKey, existing) -> existing.id().equals(suite.id())
                    ? new EvaluationSuite(existing.id(), existing.name(), existing.version(), false, existing.cases())
                    : existing);
        }
        suites.put(key, suite);
        persistEvidence();
        return suite;
    }

    /** Resolves an exact immutable suite version, or the active version when no version is supplied. */
    public synchronized EvaluationSuite resolveSuite(String suiteId, String requestedVersion) {
        require(suiteId, "suiteId");
        String normalizedVersion = requestedVersion == null ? "" : requestedVersion.trim();
        if (!normalizedVersion.isBlank()) {
            EvaluationSuite exact = suites.get(suiteKey(suiteId, normalizedVersion));
            if (exact == null) {
                throw new EvaluationSuiteVersionNotFoundException(
                        "suite version not found: " + suiteId + "@" + normalizedVersion);
            }
            return exact;
        }
        return suites.values().stream()
                .filter(suite -> suite.id().equals(suiteId) && suite.enabled())
                .findFirst()
                .orElseThrow(() -> new EvaluationSuiteNotEnabledException("Unknown or disabled suiteId: " + suiteId));
    }

    public QualityRuleSet rules() {
        return activeRules;
    }

    public QualityRuleSet updateRules(QualityRuleSet rules) {
        activeRules = rules;
        persistEvidence();
        return rules;
    }

    public QualitySnapshot snapshot(String runId) {
        find(runId);
        return snapshots.get(runId);
    }

    /** Finds an immutable quality snapshot by its public snapshot identifier. */
    public QualitySnapshot findSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return null;
        }
        return snapshots.values().stream()
                .filter(snapshot -> snapshotId.equals(snapshot.snapshotId()))
                .findFirst()
                .orElse(null);
    }

    public List<QualitySnapshot> snapshots(String skillId) {
        return snapshots(skillId, null, null, null, null);
    }

    public List<QualitySnapshot> snapshots(String skillId, String runtimeId, String mcpServerId,
                                           String llmProviderId) {
        return snapshots(skillId, null, runtimeId, mcpServerId, llmProviderId);
    }

    public List<QualitySnapshot> snapshots(String skillId, String dataSource, String runtimeId,
                                           String mcpServerId, String llmProviderId) {
        dataSource = normalizeDataSource(dataSource);
        runtimeId = normalizeEnvironmentFilter(runtimeId, "runtimeId");
        mcpServerId = normalizeEnvironmentFilter(mcpServerId, "mcpServerId");
        llmProviderId = normalizeEnvironmentFilter(llmProviderId, "llmProviderId");
        String requestedRuntimeId = runtimeId;
        String requestedMcpServerId = mcpServerId;
        String requestedLlmProviderId = llmProviderId;
        String requestedDataSource = dataSource;
        return snapshots.values().stream()
                .filter(snapshot -> skillId == null || skillId.isBlank() || skillId.equals(snapshot.skillId()))
                .filter(snapshot -> requestedDataSource == null || requestedDataSource.equals(snapshot.dataSource()))
                .filter(snapshot -> matchesEnvironment(snapshot.runtimeId(), requestedRuntimeId)
                        && matchesEnvironment(snapshot.mcpServerId(), requestedMcpServerId)
                        && matchesEnvironment(snapshot.llmProviderId(), requestedLlmProviderId))
                .sorted(Comparator.comparing(QualitySnapshot::measuredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private String normalizeDataSource(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.isBlank() || "all".equals(normalized)) return null;
        if (!Set.of("mock", "production").contains(normalized)) {
            throw new IllegalArgumentException("dataSource must be mock, production or all");
        }
        return normalized;
    }

    private boolean matchesEnvironment(String actual, String requested) {
        return requested == null || requested.isBlank() || java.util.Objects.equals(
                actual == null ? "" : actual, requested.trim());
    }

    private String normalizeEnvironmentFilter(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return null;
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return normalized;
    }

    public List<EvaluationCaseResult> results(String runId) {
        find(runId);
        return caseResults.getOrDefault(runId, List.of());
    }

    private void execute(String id, EvaluationRequest request, EvaluationSuite suite) {
        EvaluationRun queued;
        synchronized (this) {
            queued = runs.get(id);
            if (queued == null || cancellationRequests.contains(id) || queued.status() == EvaluationRunStatus.CANCELLED) {
                cancellationRequests.remove(id);
                return;
            }
            runs.put(id, new EvaluationRun(queued.id(), queued.skillId(), queued.skillVersion(), queued.suiteId(),
                    queued.suiteVersion(), EvaluationRunStatus.RUNNING, queued.providerId(), queued.evaluationProviderId(),
                    queued.dataSource(), queued.createdAt(), null, queued.totalCases(), 0, 0, "",
                    QualityGateStatus.BLOCKED, List.of("EVALUATION_NOT_COMPLETED"), queued.runtimeId(),
                    queued.mcpServerId(), queued.llmProviderId(), queued.experimentId(),
                    queued.runtimeEnvironment(), queued.mcpServerEnvironment(), queued.llmProviderEnvironment()));
            persistEvidence();
        }
        int passed = 0;
        int totalScore = 0;
        String errorCode = "";
        String dataSource = queued.dataSource();
        List<EvaluationCaseResult> evaluatedCases = new java.util.ArrayList<>();
        for (EvaluationCase evaluationCase : suite.cases()) {
            if (isCancellationRequested(id)) {
                saveCaseResults(id, evaluatedCases);
                cancellationRequests.remove(id);
                return;
            }
            RunnerExecutionRequest runnerRequest = new RunnerExecutionRequest(request.skillId(), request.skillVersion(),
                    id, suite.id(), evaluationCase.id(), request.timeoutMs(), request.scenario(), request.runtimeId(),
                    request.mcpServerId(), request.llmProviderId());
            RunnerExecutionResult executionResult = sanitizeExecutionResult(executeWithRetry(runnerRequest));
            if (!dataSource.equals(executionResult.dataSource())) {
                saveCaseResults(id, evaluatedCases);
                throw new ProviderUnavailableException(runner.providerId(), "MIXED_DATA_SOURCE");
            }
            if (isCancellationRequested(id)) {
                saveCaseResults(id, evaluatedCases);
                cancellationRequests.remove(id);
                return;
            }
            observabilityProvider.record(new RunnerExecutionSummary(id, request.skillId(), request.skillVersion(),
                    executionResult.status(), executionResult.durationMs(), executionResult.errorCode(),
                    executionResult.dataSource(), clock.instant(), request.runtimeId(), request.mcpServerId(),
                    request.llmProviderId()));
            if (executionResult.status() == RunnerExecutionStatus.TIMED_OUT) {
                evaluatedCases.add(new EvaluationCaseResult(id, evaluationCase.id(), safeCaseName(evaluationCase.id()),
                        executionResult.status(), false, 0, executionResult.durationMs(), executionResult.errorCode(),
                        executionResult.errorCode(), executionResult.dataSource(), clock.instant()));
                saveCaseResults(id, evaluatedCases);
                markTimedOut(id, executionResult);
                return;
            }
            EvaluationResult result = evaluationProvider.evaluate(evaluationCase, executionResult);
            String safeReason = result.passed()
                    ? "passed"
                    : ProviderErrorCodes.normalize(result.reason(), "EVALUATION_CASE_FAILED");
            evaluatedCases.add(new EvaluationCaseResult(id, evaluationCase.id(), safeCaseName(evaluationCase.id()),
                    executionResult.status(), result.passed(), result.score(), executionResult.durationMs(),
                    executionResult.errorCode(), safeReason, executionResult.dataSource(), clock.instant()));
            if (result.passed()) {
                passed++;
            } else if (errorCode.isBlank()) {
                errorCode = safeReason;
            }
            totalScore += result.score();
        }
        int score = suite.cases().isEmpty() ? 0 : totalScore / suite.cases().size();
        double passRate = suite.cases().isEmpty() ? 0 : (double) passed / suite.cases().size();
        StaticQualityReport staticReport = staticQualityEvaluator.evaluate(request.skillId(), request.skillVersion());
        QualityRuleSet rules = activeRules;
        QualityGate gate = qualityGateService.evaluate(rules, score, passRate, staticReport.score());
        Instant completed = clock.instant();
        EvaluationRun completedRun = new EvaluationRun(id, request.skillId(), request.skillVersion(), suite.id(),
                suite.version(), EvaluationRunStatus.COMPLETED, runner.providerId(), evaluationProvider.providerId(),
                dataSource, queued.createdAt(), completed, suite.cases().size(), passed, score, errorCode,
                gate.status(), gate.reasons(), request.runtimeId(), request.mcpServerId(), request.llmProviderId(),
                request.experimentId(), queued.runtimeEnvironment(), queued.mcpServerEnvironment(),
                queued.llmProviderEnvironment());
        synchronized (this) {
            EvaluationRun current = runs.get(id);
            if (cancellationRequests.contains(id) || current == null
                    || current.status() == EvaluationRunStatus.CANCELLED) {
                cancellationRequests.remove(id);
                return;
            }
            runs.put(id, completedRun);
            caseResults.put(id, List.copyOf(evaluatedCases));
            snapshots.put(id, new QualitySnapshot(id, request.skillId(), request.skillVersion(), suite.id(), suite.version(),
                    runner.providerId(), evaluationProvider.providerId(), dataSource, completed, score,
                    suite.cases().size(), passed, true, rules.version(), staticReport.score(), passRate,
                    gate.status(), gate.reasons(), request.runtimeId(), request.mcpServerId(), request.llmProviderId(),
                    queued.runtimeEnvironment(), queued.mcpServerEnvironment(), queued.llmProviderEnvironment()));
            persistEvidence();
        }
        cancellationRequests.remove(id);
    }

    private RunnerExecutionResult executeWithRetry(RunnerExecutionRequest request) {
        RunnerExecutionResult result = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            result = runner.execute(request);
            if (!retryPolicy.shouldRetry(result, attempt)) {
                return result;
            }
            long backoffMs = retryPolicy.backoffMs(attempt);
            if (backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new RunnerExecutionResult(RunnerExecutionStatus.CANCELLED,
                            runner.providerId(), runner.providerVersion(), runnerDataSource(), 0, "", "RETRY_INTERRUPTED");
                }
            }
        }
        return result;
    }

    private RunnerExecutionResult sanitizeExecutionResult(RunnerExecutionResult result) {
        if (result == null) {
            return new RunnerExecutionResult(RunnerExecutionStatus.FAILED, runner.providerId(),
                    runner.providerVersion(), runnerDataSource(), 0, "", "RUNNER_EXECUTION_FAILED");
        }
        String errorCode;
        if (result.status() == RunnerExecutionStatus.SUCCEEDED) {
            errorCode = ProviderErrorCodes.isStable(result.errorCode()) ? result.errorCode().trim() : "";
        } else {
            String fallback = result.status() == RunnerExecutionStatus.TIMED_OUT
                    ? "EVALUATION_TIMED_OUT"
                    : result.status() == RunnerExecutionStatus.CANCELLED
                    ? "EVALUATION_CANCELLED"
                    : "RUNNER_EXECUTION_FAILED";
            errorCode = ProviderErrorCodes.normalize(result.errorCode(), fallback);
        }
        return new RunnerExecutionResult(result.status(), result.providerId(), result.providerVersion(),
                normalizeEvidenceDataSource(result.dataSource()), result.durationMs(), result.outputHash(), errorCode);
    }

    private String runnerDataSource() {
        return normalizeEvidenceDataSource(runner.dataSource());
    }

    private String normalizeEvidenceDataSource(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("mock", "production").contains(normalized)) {
            throw new IllegalArgumentException("provider dataSource must be mock or production");
        }
        return normalized;
    }

    private static final class NoopObservabilityProvider implements ObservabilityProvider {
        @Override
        public String providerId() {
            return "noop-observability";
        }

        @Override
        public String providerVersion() {
            return "1.0";
        }

        @Override
        public void record(RunnerExecutionSummary summary) {
            // Compatibility constructor for unit tests that do not need observation.
        }
    }

    private static Map<String, EvaluationSuite> defaultSuites() {
        EvaluationSuite smoke = new EvaluationSuite("smoke", "Smoke 基础回归", "smoke-v1", true, List.of(
                new EvaluationCase("case-1", "基本成功路径"),
                new EvaluationCase("case-2", "稳定性路径")));
        return Map.of(suiteKey(smoke.id(), smoke.version()), smoke);
    }

    private static String suiteKey(String suiteId, String suiteVersion) {
        return suiteId + "\u0000" + suiteVersion;
    }

    private void restoreEvidence(QualityEvidenceState state) {
        if (state == null) return;
        if (!state.suites().isEmpty()) {
            suites.clear();
            state.suites().forEach(suite -> suites.put(suiteKey(suite.id(), suite.version()), suite));
        }
        if (state.rules() != null) {
            activeRules = state.rules();
        }
        state.runs().forEach(run -> runs.put(run.id(), run));
        state.snapshots().forEach(snapshot -> snapshots.put(snapshot.snapshotId(), snapshot));
        state.caseResults().stream().collect(java.util.stream.Collectors.groupingBy(EvaluationCaseResult::runId))
                .forEach((runId, results) -> caseResults.put(runId, List.copyOf(results)));
    }

    private synchronized void persistEvidence() {
        evidenceRepository.update(current -> new QualityEvidenceState(
                listSuites(), activeRules, runs.values().stream().toList(), snapshots.values().stream().toList(),
                caseResults.values().stream().flatMap(List::stream).toList(), current.matrixRuns(), current.matrixCases()));
    }

    private synchronized void saveCaseResults(String runId, List<EvaluationCaseResult> results) {
        if (results == null || results.isEmpty()) return;
        caseResults.put(runId, List.copyOf(results));
        persistEvidence();
    }

    private void markFailed(String id, RuntimeException failure) {
        EvaluationRun current = runs.get(id);
        if (current == null || isTerminal(current.status()) || cancellationRequests.contains(id)) return;
        String errorCode = stableFailureCode(failure);
        runs.put(id, new EvaluationRun(current.id(), current.skillId(), current.skillVersion(), current.suiteId(),
                current.suiteVersion(), EvaluationRunStatus.FAILED, current.providerId(),
                current.evaluationProviderId(), current.dataSource(), current.createdAt(), clock.instant(),
                current.totalCases(), current.passedCases(), current.score(), errorCode,
                QualityGateStatus.BLOCKED, List.of(errorCode), current.runtimeId(),
                current.mcpServerId(), current.llmProviderId(), current.experimentId(),
                current.runtimeEnvironment(), current.mcpServerEnvironment(), current.llmProviderEnvironment()));
        persistEvidence();
    }

    /**
     * Provider exceptions are converted to a bounded error code only. The exception message may contain
     * adapter details or future upstream payloads and must never become evaluation evidence.
     */
    private String stableFailureCode(RuntimeException failure) {
        if (failure instanceof ProviderUnavailableException providerFailure
                && ProviderErrorCodes.isStable(providerFailure.code())) {
            return providerFailure.code().trim();
        }
        return "EVALUATION_EXECUTION_FAILED";
    }

    private synchronized void markTimedOut(String id, RunnerExecutionResult result) {
        EvaluationRun current = runs.get(id);
        if (current == null || isTerminal(current.status()) || cancellationRequests.contains(id)) {
            return;
        }
        runs.put(id, new EvaluationRun(current.id(), current.skillId(), current.skillVersion(), current.suiteId(),
                current.suiteVersion(), EvaluationRunStatus.TIMED_OUT, current.providerId(),
                current.evaluationProviderId(), current.dataSource(), current.createdAt(), clock.instant(),
                current.totalCases(), current.passedCases(), current.score(),
                result.errorCode() == null || result.errorCode().isBlank() ? "EVALUATION_TIMED_OUT" : result.errorCode(),
                QualityGateStatus.BLOCKED, List.of("EVALUATION_TIMED_OUT"), current.runtimeId(),
                current.mcpServerId(), current.llmProviderId(), current.experimentId(),
                current.runtimeEnvironment(), current.mcpServerEnvironment(), current.llmProviderEnvironment()));
        persistEvidence();
    }

    private boolean isCancellationRequested(String id) {
        EvaluationRun current = runs.get(id);
        return cancellationRequests.contains(id) || current == null || current.status() == EvaluationRunStatus.CANCELLED;
    }

    private boolean isTerminal(EvaluationRunStatus status) {
        return status == EvaluationRunStatus.COMPLETED
                || status == EvaluationRunStatus.FAILED
                || status == EvaluationRunStatus.TIMED_OUT
                || status == EvaluationRunStatus.CANCELLED;
    }

    private EvaluationRun cancelled(EvaluationRun current) {
        return new EvaluationRun(current.id(), current.skillId(), current.skillVersion(), current.suiteId(),
                current.suiteVersion(), EvaluationRunStatus.CANCELLED, current.providerId(),
                current.evaluationProviderId(), current.dataSource(), current.createdAt(), clock.instant(),
                current.totalCases(), current.passedCases(), current.score(), "EVALUATION_CANCELLED",
                QualityGateStatus.BLOCKED, List.of("EVALUATION_CANCELLED"), current.runtimeId(),
                current.mcpServerId(), current.llmProviderId(), current.experimentId(),
                current.runtimeEnvironment(), current.mcpServerEnvironment(), current.llmProviderEnvironment());
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private String safeCaseName(String caseId) {
        return "case-" + caseId;
    }
}
