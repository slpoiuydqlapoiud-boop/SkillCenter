# Optimization Experiment Decision Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已完成的优化实验与质量/Benchmark 证据收敛为可恢复、可审计且不自动发布的决策快照。

**Architecture:** 将决策快照作为 `OptimizationExperiment` 的可选嵌套字段，与实验记录一起通过现有 JSON 原子存储恢复；服务层只读取并严格校验实验、质量快照和 Benchmark 的上下文，使用纯规则计算决策。管理员 API 暴露生成/查询端点，Quality Center 在实验行展示决策但不推进工作项或 Skill 生命周期。

**Tech Stack:** Java 21, Spring Boot, Jackson JSON persistence, JUnit 5, React, Node test runner, Vite.

**Spec:** `docs/superpowers/specs/2026-08-24-optimization-experiment-decision-design.md`

## Global Constraints

- 只允许管理员生成或查询决策。
- 不自动发布、灰度、回滚、修改 Skill 或完成优化工作项。
- 决策证据必须使用同一 Skill、候选版本、数据源、套件版本和 Runtime/MCP/LLM 上下文。
- 不保存 Prompt、输入输出正文、工具参数、文件正文或凭据。
- 持久化沿用现有 `OptimizationExperimentStore` JSON 原子替换与恢复校验。
- 生产代码必须先有一个会正确失败的测试。

---

### Task 1: 决策领域模型与规则计算

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentDecision.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentDecisionCalculator.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperiment.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentDecisionTest.java`

**Interfaces:**
- Consumes: `QualitySnapshot`, `BenchmarkResult`, existing experiment context.
- Produces: `OptimizationExperimentDecision calculate(OptimizationExperiment experiment, QualitySnapshot snapshot, BenchmarkResult benchmark, String actor, Instant evaluatedAt)` and optional decision field on `OptimizationExperiment`.

- [ ] **Step 1: Write the failing test**

  Add tests for `BLOCKED -> REJECT_CANDIDATE`, `IMPROVED + PASSED -> PROMOTE_CANDIDATE`, `REGRESSED -> REJECT_CANDIDATE`, `MIXED/NO_CHANGE -> ITERATE`, and `NOT_COMPARABLE -> NOT_COMPARABLE`; assert the decision contains only evidence IDs and context metadata.

- [ ] **Step 2: Run test to verify it fails**

  Run: `mvn.cmd -q -f apps/api/pom.xml -Dtest=OptimizationExperimentDecisionTest test`
  Expected: FAIL because the decision model/calculator does not exist.

- [ ] **Step 3: Write minimal implementation**

  Add bounded normalized decision fields and a pure calculator implementing the table in the spec. Extend `OptimizationExperiment` with a nullable decision field and a backward-compatible constructor so old JSON records without `decision` remain readable.

- [ ] **Step 4: Run test to verify it passes**

  Run the same focused Maven command; expected: PASS.

### Task 2: Persisted decision generation and idempotency

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentDecisionInvalidStateException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentStore.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentDecisionServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentStoreTest.java`

**Interfaces:**
- Consumes: `OptimizationExperimentDecisionCalculator`, `QualityEvaluationService.findSnapshot`, `BenchmarkService.list`.
- Produces: `OptimizationExperimentService.decide(String experimentId, Actor actor, String requestId)` and `OptimizationExperimentService.findDecision(String experimentId, Actor actor)`.

- [ ] **Step 1: Write the failing test**

  Test successful decision persistence, repeated `decide` returning the same decision without changing `evaluatedAt`, missing Benchmark returning the stable invalid-state exception, mismatched evidence context being rejected, and store reload preserving the nested decision.

- [ ] **Step 2: Run test to verify it fails**

  Run: `mvn.cmd -q -f apps/api/pom.xml -Dtest=OptimizationExperimentDecisionServiceTest,OptimizationExperimentStoreTest test`
  Expected: FAIL because decision methods and persisted field are missing.

- [ ] **Step 3: Write minimal implementation**

  Resolve the experiment, require `COMPLETED` and nonblank `benchmarkId`, load the snapshot and benchmark, validate every context field, compute once, and replace the experiment only when no decision exists. Store reload must accept old records with no decision and reject duplicate/malformed nested decisions through the existing persistence exception.

- [ ] **Step 4: Run test to verify it passes**

  Run the same focused Maven command; expected: PASS.

### Task 3: Admin API and stable error contract

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentControllerTest.java`

**Interfaces:**
- Consumes: service `decide` and `findDecision` methods.
- Produces: `POST /api/v1/admin/quality/optimization-experiments/{experimentId}/decision` and `GET .../{experimentId}/decision`.

- [ ] **Step 1: Write the failing test**

  Add MockMvc tests for admin success, non-admin forbidden, missing decision 404, and invalid experiment state 409 with `OPTIMIZATION_EXPERIMENT_DECISION_INVALID_STATE`.

- [ ] **Step 2: Run test to verify it fails**

  Run: `mvn.cmd -q -f apps/api/pom.xml -Dtest=OptimizationExperimentControllerTest test`
  Expected: FAIL because the endpoints and exception mapping do not exist.

- [ ] **Step 3: Write minimal implementation**

  Add controller methods with the existing `ApiResponse`/request ID conventions and map the new exception to the stable 409 error code; keep all response fields metadata-only.

- [ ] **Step 4: Run test to verify it passes**

  Run the same focused Maven command; expected: PASS.

### Task 4: Quality Center decision interaction

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/QualityCenterView.jsx`
- Test: `apps/web/tests/api-client.test.mjs`
- Test: `apps/web/tests/quality-center-view.test.mjs`

**Interfaces:**
- Consumes: API decision endpoints and existing experiment list state.
- Produces: `decideOptimizationExperiment(experimentId)`, `getOptimizationExperimentDecision(experimentId)`, and a visible decision/recommendation row.

- [ ] **Step 1: Write the failing test**

  Assert API paths/methods, a completed experiment with Benchmark shows “生成决策”, clicking it renders the decision and recommended action, and an experiment without Benchmark shows the prerequisite instead of a decision button.

- [ ] **Step 2: Run test to verify it fails**

  Run: `npm.cmd --prefix apps/web test -- api-client.test.mjs quality-center-view.test.mjs`
  Expected: FAIL because the client methods and controls are absent.

- [ ] **Step 3: Write minimal implementation**

  Add the two API methods, wire decision loading from experiment responses when present, add the button and metadata-only decision display, and preserve existing reconcile/cancel/Benchmark behavior.

- [ ] **Step 4: Run test to verify it passes**

  Run the same focused Web command; expected: PASS.

### Task 5: Documentation, regression and build verification

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Test: existing API/Web suites

- [ ] **Step 1: Update lifecycle status**

  Record the decision snapshot, stable error contract, and explicit non-automation boundary in both status documents.

- [ ] **Step 2: Run focused and full verification**

  Run:
  - `mvn.cmd -q -f apps/api/pom.xml -Dtest=OptimizationExperimentDecisionTest,OptimizationExperimentDecisionServiceTest,OptimizationExperimentControllerTest test`
  - `mvn.cmd -q -f apps/api/pom.xml test`
  - `npm.cmd --prefix apps/web test`
  - `npm.cmd --prefix apps/web run build`
  - `git diff --check`

  Expected: all tests pass, build succeeds, and `git diff --check` reports no whitespace errors.
