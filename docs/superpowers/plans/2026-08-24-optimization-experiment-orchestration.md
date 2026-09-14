# OptimizationExperiment 实验编排实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 READY_FOR_EVALUATION 优化工作项变成可幂等创建、可恢复推进、可取消、可 Benchmark 并自动回写质量证据的 OptimizationExperiment。

**Architecture:** 在现有 quality 模块中增加不可变实验记录和 JSON 原子存储；通过 `experimentId` 将实验与 EvaluationRun/Benchmark 做幂等关联。编排服务只复用现有评测、Benchmark、工作项证据绑定能力，不修改 Skill 内容或生命周期状态。

**Tech Stack:** Java 17、Spring Boot、JUnit 5、AssertJ、Jackson JSON Store；React、Vite、Node test runner。

**Spec:** `docs/superpowers/specs/2026-08-24-optimization-experiment-orchestration-design.md`

## Global Constraints

- 所有新增接口只允许 admin。
- 实验上下文由工作项复制；创建请求不得覆盖 Skill、版本、套件或执行环境。
- `suiteId` 与 `suiteVersion` 必须成对固定；不能在实验执行中隐式漂移到新的 active suite。
- GET 只能查询，不得隐式提交评测、Benchmark 或修改工作项。
- 不保存 Prompt、输入输出样本、凭据、完整 Trace 或业务文本；失败原因只使用稳定错误码。
- 不自动修改、发布、灰度、回滚或完成 Skill/优化工作项。
- 保留现有构造函数和旧调用方行为；不破坏已有 API/Web 测试。
- 当前工作区有既有改动；每次只编辑本计划列出的文件，不执行 reset、checkout 或全量格式化。

---

### Task 1: 为 EvaluationRun 增加实验关联和幂等提交

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/EvaluationRequest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/EvaluationRun.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceStore.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/QualityEvaluationServiceTest.java`

**Interfaces:**
- 两个 record 增加可选最终字段 `String experimentId`；所有现有构造函数以 `""` 委托。
- `QualityEvaluationService` 增加 `findByExperimentId(String)`，同实验 ID 且上下文一致时复用 Run。
- QualityEvidenceStore 恢复时拒绝重复非空实验 ID，兼容旧空值。

- [ ] **Step 1: Write the failing test**

```java
@Test
void reusesTheSameEvaluationRunForAnExperimentId() {
    EvaluationRequest request = new EvaluationRequest(
            "eox-query", "1.2.0", "smoke", "success", 1_000,
            "", "", "", "smoke-v1", "experiment-1");
    EvaluationRun first = service.submit(request);
    EvaluationRun second = service.submit(request);
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(service.list("eox-query"))
            .filteredOn(run -> "experiment-1".equals(run.experimentId())).hasSize(1);
}

@Test
void rejectsExperimentIdReuseWhenContextDiffers() {
    service.submit(new EvaluationRequest("eox-query", "1.2.0", "smoke", "success", 1_000,
            "", "", "", "smoke-v1", "experiment-1"));
    assertThatThrownBy(() -> service.submit(new EvaluationRequest("eox-query", "1.2.1",
            "smoke", "success", 1_000, "", "", "", "smoke-v1", "experiment-1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("experimentId");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `apps/api`:

```powershell
..\..\mvn.cmd -q -Dtest=QualityEvaluationServiceTest test
```

Expected: compilation failure because the new component and accessor do not exist.

- [ ] **Step 3: Write minimal implementation**

Normalize `experimentId` as an optional bounded identifier. In synchronized `submit`, after exact suite resolution and before generating a UUID, look up the ID; return the existing Run after comparing Skill/version/suite/runtime/MCP/LLM, otherwise reject context drift. Copy the field through queued, running, completed, timed-out, failed and cancelled Run records. Add `findByExperimentId` and persisted duplicate validation.

- [ ] **Step 4: Run the focused test and verify GREEN**

```powershell
..\..\mvn.cmd -q -Dtest=QualityEvaluationServiceTest test
```

Expected: all focused evaluation tests pass.

- [ ] **Step 5: Refactor only after green**

Extract one private context comparison helper, keep legacy constructors unchanged, and rerun the focused test.

### Task 2: 为 Benchmark 增加实验幂等键

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkRequest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkResult.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/BenchmarkServiceEnvironmentTest.java`

**Interfaces:** add optional final `experimentId` to request/result, `BenchmarkStore.findByExperimentId`, and same-context reuse in `BenchmarkService.run`; legacy constructors and calls remain unchanged.

- [ ] **Step 1: Write the failing test**

Run the same Benchmark request twice with `experiment-1`; assert one stored result and the same `benchmarkId`. Run it once with a changed candidate version; assert an `experimentId` conflict.

- [ ] **Step 2: Verify RED**

```powershell
..\..\mvn.cmd -q -Dtest=BenchmarkServiceEnvironmentTest test
```

Expected: compilation failure for the new constructor/accessor.

- [ ] **Step 3: Implement minimal idempotency**

Normalize the ID, find an existing result before comparison, compare all Skill/version/window/data-source/environment/suite fields, reuse only an exact match, and validate duplicate experiment IDs during JSON restore.

- [ ] **Step 4: Verify GREEN**

```powershell
..\..\mvn.cmd -q -Dtest=BenchmarkServiceEnvironmentTest test
```

### Task 3: 创建 OptimizationExperiment 领域模型和 JSON Store

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperiment.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentNotFoundException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentConflictException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentInvalidStateException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentPersistenceException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentDomainTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentStoreTest.java`

**Interfaces:** status exposes `normalize`/ `isTerminal`; store exposes `create`, `replace`, `find`, `findAll`, `findActiveByWorkItemId`; default path is `./data/governance/optimization-experiments.json`.

- [ ] **Step 1: Write failing tests**

Cover suite pair validation; QUEUED without Run; RUNNING requiring Run; COMPLETED requiring Run and Snapshot; failure/cancel codes; unique IDs; one active experiment per work item; and JSON restart restoration.

- [ ] **Step 2: Verify RED**

```powershell
..\..\mvn.cmd -q -Dtest=OptimizationExperimentDomainTest,OptimizationExperimentStoreTest test
```

Expected: compilation failure because model/store files do not exist.

- [ ] **Step 3: Implement the immutable model and atomic store**

Follow `OptimizationWorkItemStore`: record-level validation, `ReentrantReadWriteLock`, Jackson list restore, duplicate/semantic restore checks, one nonterminal work-item key, temporary file plus atomic move, and dedicated persistence exception.

- [ ] **Step 4: Verify GREEN**

```powershell
..\..\mvn.cmd -q -Dtest=OptimizationExperimentDomainTest,OptimizationExperimentStoreTest test
```

### Task 4: 实现实验编排服务和证据闭环

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentCreateRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentBenchmarkRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentServiceTest.java`

**Interfaces:**
- `create(request, actor, requestId)`, `list(skillId, workItemId, status, actor)`, `find(experimentId, actor)`, `reconcile(experimentId, actor, requestId)`, `cancel(experimentId, actor, requestId)`, and `benchmark(experimentId, request, actor, requestId)`.
- Creation request contains only `workItemId`; Benchmark request contains only optional `window`.

- [ ] **Step 1: Write failing service tests**

Use in-memory stores and the existing mock providers. Test: only READY_FOR_EVALUATION can create; repeated create returns the same active experiment; an unpinned work item pins the resolved active suite; completion binds QUALITY_SNAPSHOT but leaves the work item READY; timeout/failure records a stable code; queued cancellation without a Run ID works; mismatched baseline Benchmark is rejected.

- [ ] **Step 2: Verify RED**

```powershell
..\..\mvn.cmd -q -Dtest=OptimizationExperimentServiceTest test
```

Expected: compilation failure because the service/request types do not exist.

- [ ] **Step 3: Implement create and reconcile**

Read the work item, enforce READY and candidate existence, reuse an active experiment, resolve and pin the suite, persist QUEUED, submit with the experiment ID, then replace with RUNNING. Reconcile by reusing `findByExperimentId`, mapping Run terminal states, validating the Snapshot context, binding QUALITY_SNAPSHOT, then replacing with COMPLETED. On cross-store retry, recognize an already matching work-item evidence binding before declaring completion.

- [ ] **Step 4: Implement cancel and Benchmark**

Queued experiments without Run IDs go directly to CANCELLED; otherwise call evaluation cancel. Benchmark requires COMPLETED, builds context only from the experiment plus the allowed window, passes `experimentId), and binds BENCHMARK.

- [ ] **Step 5: Verify GREEN**

```powershell
..\..\mvn.cmd -q -Dtest=OptimizationExperimentServiceTest test
```

### Task 5: 暴露 Admin API 和稳定错误映射

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationExperimentController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationExperimentControllerTest.java`

**Interfaces:** base path `/api/v1/admin/quality/optimization-experiments`; POST create, GET list/find, POST reconcile/cancel/benchmark. New create returns 201; active idempotent reuse returns 200; all bodies use `ApiResponse` and request ID.

- [ ] **Step 1: Write failing controller tests**

Cover admin success for every endpoint, non-admin 403, missing experiment 404 with `OPTIMIZATION_EXPERIMENT_NOT_FOUND`, invalid state 409, and read-only GET behavior.

- [ ] **Step 2: Verify RED**

```powershell
..\..\mvn.cmd -q -Dtest=OptimizationExperimentControllerTest test
```

Expected: compilation failure because controller/handlers do not exist.

- [ ] **Step 3: Implement controller and handlers**

Follow `OptimizationWorkItemController` for actor resolution and request ID extraction. Add stable handlers for not-found, conflict, invalid-state, context/evidence and persistence errors; never expose stacks or business content.

- [ ] **Step 4: Verify GREEN**

```powershell
..\..\mvn.cmd -q -Dtest=OptimizationExperimentControllerTest test
```

### Task 6: 接入 Quality Center 最小 Web 闭环

**Files:**
- Modify: `apps/web/src/QualityCenterView.jsx`
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/tests/quality-center-view.test.mjs`

**Interfaces:** add experiment list/create/reconcile/cancel/benchmark calls under the Task 5 base path; keep state keyed by `workItemId`; never send candidate/context fields from the browser.

- [ ] **Step 1: Write the failing Web test**

With a READY work-item fixture, assert “启动实验” posts only `{ workItemId }`, displays RUNNING, reconcile displays COMPLETED while the work item remains READY, and Benchmark is available only after completion.

- [ ] **Step 2: Verify RED**

```powershell
npm.cmd test -- --test-name-pattern="experiment"
```

Expected: the new test fails because experiment data/actions are not rendered.

- [ ] **Step 3: Implement minimal UI state/actions**

Reuse existing Quality Center loading/error conventions; after mutation reload experiments and work items. Render only safe IDs/status/error codes and keep work-item lifecycle buttons independent from experiment completion.

- [ ] **Step 4: Verify GREEN and build**

```powershell
npm.cmd test -- --test-name-pattern="experiment"
npm.cmd run build
```

### Task 7: 全量验证与项目状态更新

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`

- [ ] **Step 1: Run API regression**

```powershell
cd apps/api
..\..\mvn.cmd -q test
```

Expected: zero API failures/errors.

- [ ] **Step 2: Run Web regression and build**

```powershell
cd ..\web
npm.cmd test
npm.cmd run build
```

Expected: all Web tests pass and build succeeds; existing size warnings may remain.

- [ ] **Step 3: Run whitespace verification**

```powershell
cd ..\..
git diff --check
```

Expected: no whitespace errors in files changed by this plan.

- [ ] **Step 4: Update status documents**

Record the experiment capability, exact API/Web counts, default storage path, and the fact that completion never auto-completes or publishes a Skill.

- [ ] **Step 5: Review only the scoped final diff**

```powershell
git diff --stat -- docs/superpowers/plans/2026-08-24-optimization-experiment-orchestration.md apps/api apps/web/src/QualityCenterView.jsx docs/project
git diff -- apps/api/src/main/java/com/huawei/skillcenter/quality apps/api/src/main/java/com/huawei/skillcenter/api apps/web/src/QualityCenterView.jsx
```

Confirm no unrelated dirty-worktree changes were included and no business content is persisted or returned.
