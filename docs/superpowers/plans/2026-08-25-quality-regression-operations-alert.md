# Quality Regression Operations Alert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将发布后质量回归结论接入统一运营告警状态机，形成质量发现、通知和恢复闭环。

**Architecture:** 新增只读 `QualityRegressionProbe` 边界，由 `QualityRegressionService` 从发布后评估存储按 Skill 选取最新结论并输出有限、稳定排序的健康信号。`OperationsAlertService` 通过可选 `ObjectProvider` 接入该探针，复用现有状态仓库和通知 Sink，不改变告警快照接口。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, Maven。

**Spec:** `docs/superpowers/specs/2026-08-25-quality-regression-operations-alert-design.md`

## Global Constraints

- 质量回归只读，不自动发布、回滚或变更优化工作项。
- 探针存储异常必须 fail-closed 为 `NOT_READY`，不能静默为健康。
- 告警状态键固定为 `QUALITY_REGRESSION`，事件码变化不能阻断恢复通知。
- 回归条目最多 100 条；结果必须稳定排序并保持不可变。
- 保持现有 `OperationsAlertService` 公共构造器和 `OperationsAlertSnapshot` 兼容。

---

### Task 1: Define the regression health boundary

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/QualityRegressionProbe.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/QualityRegressionHealth.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/QualityRegressionService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/QualityRegressionServiceTest.java`

**Interfaces:**
- Consumes: `OptimizationExperimentAssessmentStore.findAll(String)` and `OptimizationExperimentAssessment`.
- Produces: `QualityRegressionProbe.health()` and immutable `QualityRegressionHealth` with bounded regression entries.

- [x] **Step 1: Write failing tests**

  Cover latest-per-Skill de-duplication, active regression counting, healthy recovery, deterministic ordering and store failure becoming `NOT_READY`.

- [x] **Step 2: Run the focused test and verify RED**

  Run `mvn -q -Dtest=QualityRegressionServiceTest test`.
  Expected: compilation/test failure because the new boundary does not exist yet.

- [x] **Step 3: Implement the minimal immutable signal and service**

  Add the probe interface, validated records, stable grouping/sorting, a 100-entry cap, and fail-closed exception handling.

- [x] **Step 4: Run the focused test and verify GREEN**

  Run `mvn -q -Dtest=QualityRegressionServiceTest test`.
  Expected: all focused tests pass.

### Task 2: Connect the signal to operations alerts

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsAlertServiceTest.java`

**Interfaces:**
- Consumes: optional `QualityRegressionProbe` from Spring and the `QualityRegressionHealth` signal.
- Produces: `OperationsAlertSnapshot` entries for rule `QUALITY_REGRESSION`.

- [x] **Step 1: Write failing alert tests**

  Add tests for active regression, resolution after healthy state, stable state-key recovery when event codes change, and unavailable probe behavior.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run `mvn -q -Dtest=OperationsAlertServiceTest test`.
  Expected: compilation failure for the new constructor/probe path or assertion failure for the missing rule.

- [x] **Step 3: Implement the optional alert path**

  Add the Spring `ObjectProvider` parameter, preserve all existing overloads, and use a fixed `QUALITY_REGRESSION` state key with `count` threshold 0.

- [x] **Step 4: Run focused operations tests**

  Run `mvn -q -Dtest=OperationsAlertServiceTest,QualityRegressionServiceTest test`.
  Expected: all focused tests pass.

### Task 3: Update lifecycle documentation and verify the repository

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`

- [ ] **Step 1: Document the completed increment**

  Record the new alert rule, fail-closed behavior, test counts and one checked roadmap item.

- [ ] **Step 2: Run API, Web and lifecycle verification**

  Run `mvn -q test`, `npm test`, `npm run build`, `pwsh -NoProfile -File scripts/verify-lifecycle.ps1 -SkipSmoke`, and `git diff --check`.
  Expected: API and Web tests pass, Web build exits 0, lifecycle verifier reports completion, and the diff check has no actual whitespace errors.

- [ ] **Step 3: Recompute roadmap progress**

  Count checked and open roadmap checklist entries and report the exact percentage with the final verification evidence.
