package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OptimizationExperimentControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private OptimizationExperimentService service;
    private ActorResolver actorResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(OptimizationExperimentService.class);
        actorResolver = mock(ActorResolver.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OptimizationExperimentController(service, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class))).build();
        when(actorResolver.resolve(any(HttpServletRequest.class))).thenReturn(new Actor("admin", "admin"));
    }

    @Test
    void adminCanCreateReconcileCancelAndBenchmarkAnExperiment() throws Exception {
        OptimizationExperiment experiment = experiment(OptimizationExperimentStatus.RUNNING);
        when(service.create(any(OptimizationExperimentCreateRequest.class), any(Actor.class), anyString()))
                .thenReturn(experiment);
        when(service.reconcile(anyString(), any(Actor.class), anyString())).thenReturn(experiment);
        when(service.cancel(anyString(), any(Actor.class), anyString())).thenReturn(experiment);
        when(service.benchmark(anyString(), any(OptimizationExperimentBenchmarkRequest.class), any(Actor.class), anyString()))
                .thenReturn(experiment);

        mockMvc.perform(post("/api/v1/admin/quality/optimization-experiments")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new OptimizationExperimentCreateRequest("work-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.experimentId").value("experiment-1"));

        mockMvc.perform(post("/api/v1/admin/quality/optimization-experiments/experiment-1/reconcile")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/quality/optimization-experiments/experiment-1/cancel")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/quality/optimization-experiments/experiment-1/benchmark")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"window\":\"24h\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void developerCannotAccessExperiments() throws Exception {
        when(actorResolver.resolve(any(HttpServletRequest.class))).thenReturn(new Actor("developer", "developer"));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-experiments")
                        .header("X-User-Role", "developer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingExperimentUsesStableErrorCode() throws Exception {
        when(service.find("missing", new Actor("admin", "admin")))
                .thenThrow(new OptimizationExperimentNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-experiments/missing")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OPTIMIZATION_EXPERIMENT_NOT_FOUND"));
    }

    @Test
    void listIsReadOnlyAndPassesAdminFilters() throws Exception {
        when(service.list("skill-a", "work-1", "RUNNING", new Actor("admin", "admin")))
                .thenReturn(List.of(experiment(OptimizationExperimentStatus.RUNNING)));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-experiments")
                        .param("skillId", "skill-a").param("workItemId", "work-1").param("status", "RUNNING")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].experimentId").value("experiment-1"));

        verify(service).list("skill-a", "work-1", "RUNNING", new Actor("admin", "admin"));
    }

    @Test
    void adminCanGenerateAndReadAnExperimentDecision() throws Exception {
        OptimizationExperiment completed = experimentWithDecision();
        when(service.decide(anyString(), any(Actor.class), anyString())).thenReturn(completed);
        when(service.findDecision(anyString(), any(Actor.class))).thenReturn(completed.decision());

        mockMvc.perform(post("/api/v1/admin/quality/optimization-experiments/experiment-1/decision")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision.decision").value("PROMOTE_CANDIDATE"));
        mockMvc.perform(get("/api/v1/admin/quality/optimization-experiments/experiment-1/decision")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("PROMOTE_CANDIDATE"));
    }

    @Test
    void decisionInvalidStateUsesStableConflictCode() throws Exception {
        when(service.decide(anyString(), any(Actor.class), anyString()))
                .thenThrow(new OptimizationExperimentDecisionInvalidStateException("decision requires Benchmark evidence"));

        mockMvc.perform(post("/api/v1/admin/quality/optimization-experiments/experiment-1/decision")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPTIMIZATION_EXPERIMENT_DECISION_INVALID_STATE"));
    }

    @Test
    void missingDecisionUsesStableNotFoundCode() throws Exception {
        when(service.findDecision(anyString(), any(Actor.class)))
                .thenThrow(new OptimizationExperimentDecisionNotFoundException("experiment-1"));

        mockMvc.perform(get("/api/v1/admin/quality/optimization-experiments/experiment-1/decision")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OPTIMIZATION_EXPERIMENT_DECISION_NOT_FOUND"));
    }

    @Test
    void adminCanCaptureAndListPostReleaseObservations() throws Exception {
        OptimizationExperimentObservationService observationService = mock(OptimizationExperimentObservationService.class);
        MockMvc observationMvc = MockMvcBuilders.standaloneSetup(
                        new OptimizationExperimentController(service, observationService, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class))).build();
        OptimizationExperimentObservation observation = new OptimizationExperimentObservation(
                "observation-1", "experiment-1", "skill-a", "1.1.0", "production", "", "", "", "24h",
                Instant.parse("2026-08-24T02:00:00Z"), "admin", 4, 3, 1, 0, 0, 75, 120, "CAPTURED");
        when(observationService.capture(anyString(), any(OptimizationExperimentObservationRequest.class),
                any(Actor.class), anyString())).thenReturn(observation);
        when(observationService.list("experiment-1", new Actor("admin", "admin")))
                .thenReturn(List.of(observation));

        observationMvc.perform(post("/api/v1/admin/quality/optimization-experiments/experiment-1/observations")
                        .header("X-User-Role", "admin")
                        .contentType("application/json").content("{\"window\":\"24h\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.observationId").value("observation-1"));
        observationMvc.perform(get("/api/v1/admin/quality/optimization-experiments/experiment-1/observations")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].observationStatus").value("CAPTURED"));
    }

    @Test
    void adminCanCreateListAndReadPostReleaseAssessments() throws Exception {
        OptimizationExperimentAssessmentService assessmentService = mock(OptimizationExperimentAssessmentService.class);
        MockMvc assessmentMvc = MockMvcBuilders.standaloneSetup(
                        new OptimizationExperimentController(service, null, assessmentService, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class))).build();
        OptimizationExperimentAssessment assessment = assessment("assessment-1");
        when(assessmentService.assess(anyString(), any(OptimizationExperimentAssessmentRequest.class),
                any(Actor.class), anyString())).thenReturn(assessment);
        when(assessmentService.list("experiment-1", new Actor("admin", "admin")))
                .thenReturn(List.of(assessment));
        when(assessmentService.find("assessment-1", new Actor("admin", "admin"))).thenReturn(assessment);

        assessmentMvc.perform(post("/api/v1/admin/quality/optimization-experiments/experiment-1/assessments")
                        .header("X-User-Role", "admin")
                        .contentType("application/json")
                        .content("{\"observationId\":\"observation-1\",\"action\":\"KEEP\",\"note\":\"保留\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assessmentId").value("assessment-1"));
        assessmentMvc.perform(get("/api/v1/admin/quality/optimization-experiments/experiment-1/assessments")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conclusion").value("HEALTHY"));
        assessmentMvc.perform(get("/api/v1/admin/quality/optimization-experiments/experiment-1/assessments/assessment-1")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("KEEP"));
    }

    @Test
    void missingAssessmentUsesStableErrorCode() throws Exception {
        OptimizationExperimentAssessmentService assessmentService = mock(OptimizationExperimentAssessmentService.class);
        MockMvc assessmentMvc = MockMvcBuilders.standaloneSetup(
                        new OptimizationExperimentController(service, null, assessmentService, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class))).build();
        when(assessmentService.find("missing", new Actor("admin", "admin")))
                .thenThrow(new OptimizationExperimentAssessmentNotFoundException("missing"));

        assessmentMvc.perform(get("/api/v1/admin/quality/optimization-experiments/experiment-1/assessments/missing")
                        .header("X-User-Role", "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OPTIMIZATION_EXPERIMENT_ASSESSMENT_NOT_FOUND"));
    }

    private OptimizationExperiment experiment(String status) {
        Instant time = Instant.parse("2026-08-24T01:02:03Z");
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "mock", "", "", "", "smoke", "smoke-v1", status,
                status.equals(OptimizationExperimentStatus.QUEUED) ? "" : "run-1", "", "", "",
                "admin", time, "admin", time);
    }

    private OptimizationExperiment experimentWithDecision() {
        Instant time = Instant.parse("2026-08-24T01:02:03Z");
        OptimizationExperimentDecision decision = new OptimizationExperimentDecision(
                "decision-experiment-1", "experiment-1", "skill-a", "1.0.0", "1.1.0", "mock", "", "", "",
                "smoke", "smoke-v1", "snapshot-1", "benchmark-1", QualityGateStatus.PASSED, "IMPROVED",
                OptimizationExperimentDecision.PROMOTE_CANDIDATE, "BENCHMARK_IMPROVED", "reason",
                "提交人工发布审核", "admin", time);
        return new OptimizationExperiment("experiment-1", "work-1", "skill-a", "1.0.0", "1.1.0",
                "mock", "", "", "", "smoke", "smoke-v1", OptimizationExperimentStatus.COMPLETED,
                "run-1", "snapshot-1", "benchmark-1", "", "admin", time, "admin", time, decision);
    }

    private OptimizationExperimentAssessment assessment(String assessmentId) {
        Instant time = Instant.parse("2026-08-24T02:00:00Z");
        OptimizationExperimentAssessment.Metrics metrics = new OptimizationExperimentAssessment.Metrics(
                5, 5, 0, 0, 0, 100, 80, time);
        return new OptimizationExperimentAssessment(assessmentId, "experiment-1", "work-1", "skill-a", "1.0.0",
                "1.1.0", "production", "", "", "", "24h", "observation-1", metrics, metrics, 95, 1_000, 5,
                OptimizationExperimentAssessment.HEALTHY, "POST_RELEASE_HEALTHY", OptimizationExperimentAssessment.KEEP,
                OptimizationExperimentAssessment.KEEP, "保留", "admin", time);
    }
}
