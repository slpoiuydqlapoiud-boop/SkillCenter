# Skill 跨执行环境兼容性矩阵实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为一个 Skill 版本在多个 Agent Runtime、MCP Server 和 LLM Provider 组合上生成可持久化、可聚合、可作为发布证据的兼容性矩阵运行。

**Architecture:** 兼容性矩阵作为质量证据的一部分扩展 `QualityEvidenceState`，通过 `QualityEvidenceRepository` 的原子 read-modify-write 入口与单次评测、保留治理共享 JSON 文件，避免并发写覆盖。矩阵服务负责确定性组合、环境版本快照、子评测编排和聚合；发布门禁只读取显式标记为 `releaseGateRequired` 的完成矩阵，旧版本和无矩阵证据保持兼容。

**Tech Stack:** Java 21, Spring Boot 3.4, Jackson, JUnit 5, MockMvc, React, Node built-in test runner。

**Spec:** `docs/superpowers/specs/2026-08-24-skill-compatibility-matrix-design.md`

## Global Constraints

- 只接受管理员创建、查询和取消矩阵；开发者不能读取矩阵详情或发布门禁证据。
- 每个环境维度最多 10 个资产，组合总数最多 100 个；ID 去重、排序并确定性生成组合。
- 创建和开始每个组合前都校验目录资产为 `ACTIVE`；记录目录版本/能力/Provider ID 快照。
- `dataSource` 只允许 `mock` 或 `production`，当前创建接口固定默认为 `mock`，不把 `CONTRACT_ONLY` 当作真实就绪。
- 不保存 Prompt、输入输出、文件正文、工具参数、endpoint、Token 或凭据；异常只保留稳定错误码。
- 旧版 `QualityEvidenceState` JSON 缺少矩阵字段时按空列表恢复；旧单次评测、Benchmark、运行查询和无矩阵发布继续工作。
- 不重置、覆盖或提交当前工作区已有用户改动；本项目共享脏工作树，不创建 Git commit。

---

### Task 1: Matrix domain records and deterministic combination builder

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixPolicy.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixCaseStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixRun.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixCase.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixCreateRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixCombination.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixCombinationBuilder.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/CompatibilityMatrixDomainTest.java`

**Interfaces:**
- `CompatibilityMatrixCombinationBuilder.build(List<String> runtimeIds, List<String> mcpServerIds, List<String> llmProviderIds): List<CompatibilityMatrixCombination>` returns sorted, deduplicated combinations and treats an empty dimension as one empty value.
- `CompatibilityMatrixCreateRequest` validates `policy`, `minimumPassRate`, `scenario`, `timeoutMs`, `releaseGateRequired` and the three bounded ID lists without accepting provider-private fields.
- `CompatibilityMatrixRun` and `CompatibilityMatrixCase` are immutable records with bounded IDs, stable enum states, non-negative counters and terminal-time validation.
- `CompatibilityMatrixRun` also persists normalized `scenario` and bounded `timeoutMs`, so a service restart can resume active matrices without reconstructing request-only state.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void builderDeduplicatesAndSortsCartesianCombinations() {
    var combinations = CompatibilityMatrixCombinationBuilder.build(
            List.of("runtime-b", "runtime-a", "runtime-a"),
            List.of("mcp-a", "mcp-b"),
            List.of("llm-a"));

    assertThat(combinations).containsExactly(
            new CompatibilityMatrixCombination("runtime-a", "mcp-a", "llm-a"),
            new CompatibilityMatrixCombination("runtime-a", "mcp-b", "llm-a"),
            new CompatibilityMatrixCombination("runtime-b", "mcp-a", "llm-a"),
            new CompatibilityMatrixCombination("runtime-b", "mcp-b", "llm-a"));
}

@Test
void builderRejectsMoreThanOneHundredCombinations() {
    assertThatThrownBy(() -> CompatibilityMatrixCombinationBuilder.build(
            identifiers(10), identifiers(10), identifiers(2)))
            .hasMessageContaining("100");
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run `mvn.cmd -q -Dtest=CompatibilityMatrixDomainTest test` from `apps/api`. It must fail because the matrix records and combination builder do not exist.

- [ ] **Step 3: Implement the minimal immutable domain**

Normalize blank dimensions to `List.of("")`, trim and validate IDs using `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`, reject an empty all-dimension matrix, reject `MIN_PASS_RATE` outside `[0,1]`, require `minimumPassRate == 1` for `ALL_MUST_PASS`, and enforce the 10-per-dimension/100-total limits before materializing combinations.

- [ ] **Step 4: Run the focused domain test**

Run the same Maven command and verify valid records, invalid policies, duplicate IDs, empty dimensions, counter bounds and terminal timestamps all pass.

### Task 2: Shared quality evidence state and atomic persistence

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceState.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceRepository.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/CompatibilityMatrixEvidencePersistenceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/QualityEvidencePersistenceTest.java`

**Interfaces:**
- Extend `QualityEvidenceState` with `List<CompatibilityMatrixRun> matrixRuns` and `List<CompatibilityMatrixCase> matrixCases`, while retaining existing constructors.
- Add `QualityEvidenceRepository.update(UnaryOperator<QualityEvidenceState> updater): QualityEvidenceState`; the JSON implementation executes load, transform, validation and atomic replacement under one lock.
- Existing single-evaluation persistence must preserve matrix fields, and matrix updates must preserve suites, rules, runs, snapshots and case results.

- [ ] **Step 1: Write failing persistence tests** for old JSON recovery, matrix/case round-trip, duplicate IDs, parent mismatch, sub-evaluation context mismatch and concurrent read-modify-write preservation.
- [ ] **Step 2: Run focused persistence tests** with `mvn.cmd -q -Dtest=CompatibilityMatrixEvidencePersistenceTest,QualityEvidencePersistenceTest test` and verify the new state fields/atomic method are missing.
- [ ] **Step 3: Implement backward-compatible state and atomic mutation**; update `QualityEvaluationService.persistEvidence()` to use `update` and retain existing matrix fields, and update retention deletion to cascade matrix cases without deleting referenced single-evaluation evidence.
- [ ] **Step 4: Run focused persistence and existing quality tests**; verify malformed matrix evidence fails closed with `QUALITY_EVIDENCE_PERSISTENCE_FAILED` and no sensitive fields appear in serialized JSON.

### Task 3: Matrix orchestration service and administrator API

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixNotFoundException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixConflictException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/CompatibilityMatrixServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/CompatibilityMatrixControllerTest.java`

**Interfaces:**
- `CompatibilityMatrixService.create(CompatibilityMatrixCreateRequest request, Actor actor, String requestId): CompatibilityMatrixRun` validates suites and environments, snapshots environment metadata, persists the queued matrix, then starts the bounded background orchestrator.
- `list(skillId, skillVersion, status, dataSource, Actor actor): List<CompatibilityMatrixRun>` and `find(matrixRunId, Actor actor)` are admin-only.
- `cases(matrixRunId, Actor actor): List<CompatibilityMatrixCase>` returns only sanitized combination evidence.
- `cancel(matrixRunId, Actor actor, String requestId): CompatibilityMatrixRun` is idempotent for terminal matrices.
- Routes are `/api/v1/admin/quality/compatibility-matrices`, `/{matrixRunId}`, `/{matrixRunId}/cases`, and `/{matrixRunId}/cancel`.

- [ ] **Step 1: Write failing service tests** for active environment snapshotting, combination creation, duplicate active release-gate conflict, child evaluation linkage, policy aggregation, cancellation and stable error codes.
- [ ] **Step 2: Run the focused service tests** with `mvn.cmd -q -Dtest=CompatibilityMatrixServiceTest test` and confirm the service/controller are absent.
- [ ] **Step 3: Implement creation and orchestration**: persist `scenario/timeoutMs`, create one child `EvaluationRequest` per combination, use a bounded worker to submit and await terminal child runs, update each case atomically, and aggregate only terminal child results. On service startup resume `QUEUED/RUNNING` matrices from persisted request parameters; add `QualityEvaluationService.awaitTerminal(String runId, Duration timeout)` with bounded polling so the matrix worker never waits indefinitely.
- [ ] **Step 4: Implement MockMvc routes and exception mappings** for admin-only access, 202 creation, 200 list/detail/cases, 409 active conflict, 404 missing matrix and idempotent cancellation; return only `ApiResponse`/stable error envelopes.
- [ ] **Step 5: Run focused service/controller tests** and verify failed/timeout/cancelled child evaluations continue other combinations and produce `BLOCKED` rather than leaking exception text.

### Task 4: Release gate and retention integration

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/QualityEvaluationReleaseGate.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/QualityGateBlockedException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionPreview.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionExecutionResult.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/AdminRetentionController.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/CompatibilityMatrixReleaseGateTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/RetentionServiceTest.java`

**Interfaces:**
- `QualityEvaluationReleaseGate` reads the latest explicit `releaseGateRequired` matrix for the target Skill/version and preserves no-matrix compatibility.
- Add stable reasons `COMPATIBILITY_MATRIX_INCOMPLETE` and `COMPATIBILITY_MATRIX_BLOCKED`; exception details expose counts and matrix ID only.
- Retention preview/execution expose `compatibilityMatrixEligibleCount` and `compatibilityMatrixDeleted`, with repeated execution remaining idempotent.

- [ ] **Step 1: Write failing gate tests** for no matrix, queued/running matrix, cancelled matrix, blocked matrix and passed matrix; prove review state remains unchanged when blocked.
- [ ] **Step 2: Run focused gate/retention tests** and confirm matrix evidence is not yet consulted or counted.
- [ ] **Step 3: Implement gate lookup and retention cascade**; select the newest required matrix for the exact Skill/version/data source, never use a matrix from another version/environment policy, and delete child cases before the parent run.
- [ ] **Step 4: Run focused gate/retention and existing review tests**; verify legacy release and retention response constructors remain compatible.

### Task 5: Quality center matrix workbench

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/QualityCenterView.jsx`
- Modify: `apps/web/src/styles.css`
- Modify: `apps/web/tests/operations-metrics.test.mjs`
- Modify: `apps/web/tests/quality-center-view.test.mjs`

**Interfaces:**
- Add `createCompatibilityMatrix`, `listCompatibilityMatrices`, `getCompatibilityMatrix`, `listCompatibilityMatrixCases` and `cancelCompatibilityMatrix` API methods.
- Add a matrix panel that uses current Skill/version context, multi-selects catalog assets by kind, computes the 100-combination limit locally, displays status/evidence and preserves the existing single-context form.

- [ ] **Step 1: Write failing API/UI tests** for route/query encoding, multi-select catalog options, combination limit, creation request, polling/detail rendering, cancellation and missing-API fallback.
- [ ] **Step 2: Run focused Web tests** with `npm.cmd test -- --test-name-pattern="compatibility matrix"` and confirm the methods/panel are absent.
- [ ] **Step 3: Implement API methods and matrix state**; avoid adding matrix calls to old mocks unless the method exists, and keep all rendered fields limited to IDs, versions, statuses, scores and stable error codes.
- [ ] **Step 4: Implement panel interactions and focused styles**; refresh list/detail after creation and cancellation, disable non-ACTIVE assets, and show an explicit unavailable state when the catalog or matrix API is missing.
- [ ] **Step 5: Run focused Web tests and the existing QualityCenter suite**; verify existing single-environment behavior remains green.

### Task 6: Documentation and full verification

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`
- Modify: `docs/project/M11-external-integration-runbook.md`

- [ ] **Step 1: Document** the matrix API, aggregation policy, release-gate semantics, retention fields and Mock/real Provider boundary; explicitly state that matrix evidence does not prove production integration before external gates pass.
- [ ] **Step 2: Run Web tests and production build** with `npm.cmd test` and `npm.cmd run build` from `apps/web`.
- [ ] **Step 3: Run API regression and count Surefire results** with `mvn.cmd -q test` and a PowerShell count of `target/surefire-reports/TEST-*.xml`; require zero failures, errors and skipped tests.
- [ ] **Step 4: Run `git diff --check` and `pwsh -NoProfile -File scripts/verify-lifecycle.ps1 -SkipBuild -SkipSmoke`** from the repository root.
- [ ] **Step 5: Record** verified counts, matrix-specific evidence, remaining external deployment gates and the fact that the long-term Skill lifecycle goal remains active; do not create a commit.
