# 优化实验发布后效果评估实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将发布后运行观察转换为同口径、可审计、可人工处置的优化实验效果评估证据。

**Architecture:** 新增纯规则评估器、不可变 JSON Assessment Store 和管理员服务。服务读取既有 OptimizationExperimentObservation 与 RuntimeOperationsService，使用候选观察的结束时间采集源版本基线，持久化比较快照和人工动作；不改变发布状态、不自动回滚、不调用外部 Provider。

**Tech Stack:** Java 21, Spring Boot, Jackson JSON store, JUnit 5, Mockito, React, Node test runner.

**Spec:** `docs/superpowers/specs/2026-08-24-optimization-post-release-assessment-design.md`

## Global Constraints

- 只允许管理员操作；实验必须 `COMPLETED + PROMOTE_CANDIDATE` 且候选版本曾经发布。
- 候选与基线必须使用同一窗口结束时间、数据源和 Runtime/MCP/LLM 上下文。
- 观察与评估只保存聚合指标和稳定 ID，不保存业务正文、Prompt、工具参数或凭据。
- 评估记录追加写入、重启可恢复、主键唯一且不可变。
- 人工动作只保存建议和审计，不自动回滚或改变 Provider readiness。

---

### Task 1: 建立纯规则评估模型和失败测试

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessment.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentCalculator.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentCalculatorTest.java`

**Interfaces:**
- Consumes: `OptimizationExperimentObservation`, `RuntimeOperationsSnapshot`, `OptimizationSuggestionThresholds`.
- Produces: `OptimizationExperimentAssessmentCalculator.calculate(...)` returning conclusion, reason code and recommendation; Assessment validates bounded metrics and allowed manual actions.

- [x] **Step 1: Write the failing tests** for insufficient samples, regression, healthy and inconclusive/mixed metrics, plus invalid action/numeric input.
- [x] **Step 2: Run the focused test** with `mvn.cmd -q -f apps/api/pom.xml "-Dtest=OptimizationExperimentAssessmentCalculatorTest" test`; expected failure because the model/calculator does not exist.
- [x] **Step 3: Implement the minimum pure calculator and immutable record** using the exact conclusion/reason/action table in the spec.
- [x] **Step 4: Run the focused test again** and require all cases to pass.

### Task 2: Add append-only Assessment Store and service tests

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentNotFoundException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentAssessmentServiceTest.java`

**Interfaces:**
- Consumes: `OptimizationExperimentStore`, `OptimizationExperimentObservationStore`, `RuntimeOperationsService`, `OptimizationSuggestionThresholdsStore`, `GovernanceStore`.
- Produces: `list(experimentId, Actor)` and `assess(experimentId, request, Actor, requestId)`; audit action `OPTIMIZATION_EXPERIMENT_ASSESSED`.

- [x] **Step 1: Write failing tests** for restart ordering, candidate/experiment context mismatch, unpublished candidate, same-window baseline query, and audit metadata.
- [x] **Step 2: Run the focused store/service tests** and verify expected missing-type failures.
- [x] **Step 3: Implement atomic append persistence, duplicate ID recovery validation, context checks, baseline snapshot query and audit write.
- [x] **Step 4: Run focused tests** and verify all pass without storing sensitive payloads.

### Task 3: Expose administrator API and contract tests

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentControllerTest.java`

**Interfaces:**
- Consumes: `OptimizationExperimentAssessmentService`.
- Produces: nested `GET .../{experimentId}/assessments` and `POST .../{experimentId}/assessments` returning `ApiResponse`.

- [x] **Step 1: Add controller tests** for admin list/create, developer forbidden, and stable missing-assessment error.
- [x] **Step 2: Run `OptimizationExperimentControllerTest`** and verify the new endpoint tests fail.
- [x] **Step 3: Wire the service and exception mappings** while retaining the compatibility constructor used by existing tests.
- [x] **Step 4: Run the controller test** and confirm all routes pass.

### Task 4: Integrate controlled work-item evidence and Quality Center

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItem.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemService.java`
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/QualityCenterView.jsx`
- Modify: `apps/web/src/styles.css`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemServiceTest.java`
- Test: `apps/web/tests/api-client.test.mjs`
- Test: `apps/web/tests/quality-center-view.test.mjs`

**Interfaces:**
- Consumes: `POST_RELEASE_ASSESSMENT` evidence type and assessment API.
- Produces: context validation for assessment IDs and Quality Center display of conclusion, baseline/candidate delta, recommendation and manual action.

- [x] **Step 1: Add failing evidence and UI tests** proving mismatched assessment IDs are rejected and a Promote experiment can be assessed and shown.
- [x] **Step 2: Run focused API/Web tests** and verify failures are caused by missing evidence/API/UI behavior.
- [x] **Step 3: Add controlled evidence matching and UI action flow**; keep actions explicit and non-automatic.
- [x] **Step 4: Run focused tests** and verify assessment evidence remains traceable.

### Task 5: Full verification and lifecycle documentation

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/RetentionServiceTest.java`

- [x] **Step 1: Run focused Web/API tests; full commands remain the final verification gate.**
- [x] **Step 2: Integrate observation and assessment cleanup into RetentionService with regression coverage.**
- [x] **Step 3: Run full Web/API verification and `git diff --check`.**
- [x] **Step 4: Update counts and document that assessment is manual/non-rollback and external Provider readiness remains separate.**
