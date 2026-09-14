# 受控发布晋级与回滚评审实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为已发布 Skill 版本建立可审计的 STAGING/PRODUCTION 发布批次、人工审批、受控晋级和回滚评审控制面。

**Architecture:** 在新的 `release` 模块中增加不可变发布上下文、可变状态聚合和 JSON 原子 Store；通过 `QualityReleaseGate.evaluate` 冻结质量证据，通过 `ReleaseTarget` 端口执行晋级/回滚。本地默认使用确定性 Mock Target，真实 Runtime/CD 系统继续保持 fail-closed 契约边界。

**Tech Stack:** Java 21, Spring Boot, Jackson JSON store, JUnit 5, Mockito, React, Node test runner.

**Spec:** `docs/superpowers/specs/2026-08-24-controlled-release-promotion-design.md`

## Global Constraints

- 不改变 `SkillVersion.status` 的既有 `published/deprecated/withdrawn` 语义；ReleaseRecord 单独表示环境晋级事实。
- ReleaseRecord 的 Skill、版本、制品哈希、目标环境、门禁快照和幂等键创建后不可变。
- `STAGING` 和 `PRODUCTION` 均需要门禁快照；`PRODUCTION` 不允许 `NO_EVIDENCE`。
- `reviewer` 只能批准 STAGING；PRODUCTION 批准、晋级和回滚必须由 admin 执行。
- 发布请求者不能批准同一批次；回滚评审提交者不能批准自己的回滚。
- 目标执行失败必须保存稳定错误码并进入 `FAILED`，不能猜测外部状态或标记为成功。
- 不保存 Prompt、输入输出、Trace 正文、工具参数、凭据、环境变量或 Provider 异常正文。
- 本阶段不扩展 RetentionService，不实现真实 Kubernetes/OpenClaw/MCP/CD 部署。
- 保留当前工作区既有用户修改，不执行 reset、checkout、删除或提交。

---

### Task 1: 冻结门禁快照端口与发布领域模型

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseEnvironment.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseGateSnapshot.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseRecord.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/QualityReleaseGate.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/QualityEvaluationReleaseGate.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/release/ReleaseRecordTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/QualityEvaluationReleaseGateTest.java`

**Interfaces:**
- `ReleaseEnvironment.from(String)` returns `STAGING` or `PRODUCTION`.
- `ReleaseStatus` exposes `terminal()` and `canTransitionTo(ReleaseStatus)` for the exact state graph in the spec.
- `QualityReleaseGate.evaluate(String skillId, String version)` returns `ReleaseGateSnapshot`; its default implementation calls existing `ensurePublishable` and returns a bounded `PASSED` snapshot, preserving existing lambda/test compatibility.
- `ReleaseRecord` exposes `request(...)`, `approve(...)`, `reject(...)`, `promoting(...)`, `promoted(...)`, `failed(...)`, `rollbackReview(...)`, `rollingBack(...)`, `rolledBack(...)` and rejects illegal transitions or immutable context mutation; rollback review metadata is stored separately from the original request context.

- [ ] **Step 1: Write failing domain tests** for the complete state graph, terminal-state rejection, immutable context, production `NO_EVIDENCE` rejection, bounded identifiers, reviewer/admin policy helpers, and gate snapshot normalization.

```java
@Test
void promotesOnlyAfterApprovalAndKeepsReleaseContextImmutable() {
    ReleaseRecord requested = ReleaseRecord.request(
            "release-1", "skill-a", "1.2.0", "sha-1", ReleaseEnvironment.STAGING,
            ReleaseGateSnapshot.passed(Instant.parse("2026-08-24T01:00:00Z")), "idem-1", "admin",
            Instant.parse("2026-08-24T01:00:00Z"));
    ReleaseRecord promoted = requested.approve("admin-2", Instant.parse("2026-08-24T01:01:00Z"))
            .promoting("target-1", Instant.parse("2026-08-24T01:02:00Z"))
            .promoted("target-1", Instant.parse("2026-08-24T01:03:00Z"));

    assertThat(promoted.status()).isEqualTo(ReleaseStatus.PROMOTED);
    assertThat(promoted.sha256()).isEqualTo("sha-1");
    assertThatThrownBy(() -> promoted.approve("admin-3", Instant.now()))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run the focused tests and confirm the missing domain APIs fail.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=ReleaseRecordTest,QualityEvaluationReleaseGateTest" test`

Expected: compilation or assertion failure because the release model and snapshot method do not exist.

- [ ] **Step 3: Implement the minimum domain records and gate snapshot method.**

`ReleaseGateSnapshot` must contain `checkedAt`, `outcome`, `reasonCodes`, optional `qualitySnapshotId`, `optimizationExperimentId`, `optimizationDecision`, `compatibilityMatrixId`, `dataSource`, `suiteId`, `suiteVersion`, `runtimeId`, `mcpServerId`, and `llmProviderId`; normalize all optional context to empty strings and reject unbounded values.

Refactor `QualityEvaluationReleaseGate.ensurePublishable` into an internal evaluation path that returns the latest matching quality snapshot, optimization experiment decision, and release-gated compatibility matrix IDs while preserving existing block reason codes. A default interface method must keep existing custom lambdas source-compatible.

- [ ] **Step 4: Run the focused tests and verify the state graph and gate evidence pass.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=ReleaseRecordTest,QualityEvaluationReleaseGateTest" test`

Expected: all focused tests pass and no sensitive payload is present in the snapshot fields.

### Task 2: Add ReleaseTarget port and atomic persistence

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseTarget.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseTargetResult.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/MockReleaseTarget.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseRecordStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleasePersistenceException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseConflictException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/release/ReleaseRecordStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/release/MockReleaseTargetTest.java`

**Interfaces:**
- `ReleaseTarget.promote(ReleaseRecord)` and `ReleaseTarget.rollback(ReleaseRecord)` return `ReleaseTargetResult` containing `status`, `reasonCode`, `externalReference`, `durationMs`, and `completedAt` only.
- `MockReleaseTarget` returns deterministic success for normal release IDs and deterministic `RELEASE_TARGET_FAILED` for IDs containing `fail`; it never returns payloads.
- `ReleaseRecordStore.findAll(String skillId, String version, ReleaseEnvironment environment, ReleaseStatus status)`, `find(String releaseId)`, `findByIdempotencyKey(String key)`, `findActiveBusinessKey(...)`, `create(ReleaseRecord)`, and `replace(ReleaseRecord)` use read/write locks and atomic temp-file moves.

- [ ] **Step 1: Write failing store/target tests** for restart recovery, duplicate release ID, duplicate active business key, idempotency lookup, newest-first ordering, atomic replace, and Mock success/failure result safety.
- [ ] **Step 2: Run the focused tests and verify missing store/target failures.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=ReleaseRecordStoreTest,MockReleaseTargetTest" test`

- [ ] **Step 3: Implement JSON atomic persistence and deterministic target behavior.**

On load, reject duplicate release IDs, invalid state/context combinations, and malformed target result fields. `replace` must verify the release ID exists and reject changes to the immutable context fields.

- [ ] **Step 4: Run the focused tests and verify persistence across a new Store instance.**

### Task 3: Implement release service, permissions, gates and audit

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseRejectionRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/RollbackReviewRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseNotFoundException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseInvalidStateException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseTargetException.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/release/ReleaseServiceTest.java`

**Interfaces:**
- `ReleaseService.request(ReleaseRequest, Actor, String)` creates or idempotently returns a `REQUESTED` record after loading the version from `GovernanceStore` and evaluating the gate.
- `ReleaseService.list(...)` and `find(...)` require `reviewer` or `admin`; write methods enforce the role matrix in the spec.
- `ReleaseService.approve`, `reject`, `promote`, `rollbackReview`, and `rollback` transition the aggregate, persist the result, call `ReleaseTarget` only for promote/rollback, and write allow-listed audit metadata.
- `ReleaseService.rollbackReview` validates optional `assessmentId` against `OptimizationExperimentAssessmentStore` and requires `targetVersion` or `targetReleaseId`.

- [ ] **Step 1: Write failing service tests** for quality-gate blocking, production no-evidence blocking, staging request success, same idempotency key reuse, active business-key conflict, reviewer staging approval, reviewer production denial, requester self-approval denial, target failure, restart recovery of in-flight states, and rollback-review assessment context.
- [ ] **Step 2: Run the focused service test and confirm missing service behavior.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=ReleaseServiceTest" test`

- [ ] **Step 3: Implement service orchestration and configuration.**

Add `skill-center.release-storage` with default `./data/governance/releases.json`. Inject `Clock` for deterministic timestamps. On startup, normalize `PROMOTING` and `ROLLING_BACK` records to `FAILED` with `RELEASE_EXECUTION_UNKNOWN` before serving requests.

- [ ] **Step 4: Run focused service tests and inspect audit metadata for payload leakage.**

### Task 4: Expose administrator API and controller contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/release/ReleaseControllerTest.java`

**Interfaces:**
- Implement the exact endpoints from the spec under `/api/v1/admin/releases`.
- Every response wraps `ApiResponse<ReleaseRecord>` and preserves `requestId`.
- Map not-found, invalid-state, conflict, gate-blocked, forbidden, persistence and target errors to stable API codes and safe messages.

- [ ] **Step 1: Write failing MockMvc tests** for list/detail/request/approve/reject/promote/rollback-review/rollback, role denial, stable errors, requestId propagation and no internal exception text.
- [ ] **Step 2: Run `ReleaseControllerTest` and verify routes fail before implementation.**
- [ ] **Step 3: Implement controller mappings and exception mappings without changing existing API contracts.**
- [ ] **Step 4: Run the focused controller test and confirm all routes pass.**

### Task 5: Integrate Quality Center release control

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/QualityCenterView.jsx`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/tests/api-client.test.mjs`
- Test: `apps/web/tests/quality-center-view.test.mjs`

**Interfaces:**
- Add `listReleases`, `getRelease`, `createRelease`, `approveRelease`, `rejectRelease`, `promoteRelease`, `requestReleaseRollbackReview`, and `rollbackRelease` client methods.
- Add a release panel that renders safe status, target environment, gate reasons, version/hash prefix, assessment linkage and available explicit actions.
- Page load may read release data but must never issue write calls; every write is a button/form action with loading and safe error state.

- [ ] **Step 1: Write failing Web tests** for API path/body encoding, release status rendering, STAGING approval action, PRODUCTION admin-only messaging, explicit promote/rollback-review actions, and hidden payload fields.
- [ ] **Step 2: Run focused Node tests and confirm missing client/UI behavior.**

Run: `node --test apps/web/tests/api-client.test.mjs apps/web/tests/quality-center-view.test.mjs`

- [ ] **Step 3: Implement client methods and the controlled release panel.**
- [ ] **Step 4: Run focused Node tests and verify no automatic write calls occur during load.**

### Task 6: Documentation and full verification

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/superpowers/specs/2026-08-24-controlled-release-promotion-design.md`

- [ ] **Step 1: Document release control-plane endpoints, Mock/CONTRACT_ONLY target boundary, approval roles, and rollback review semantics.**
- [ ] **Step 2: Run Web full tests and production build.**

Run: `npm.cmd --prefix apps/web test`

Run: `npm.cmd --prefix apps/web run build`

- [ ] **Step 3: Run API full tests and aggregate Surefire XML.**

Run: `mvn.cmd -q -f apps/api/pom.xml test`

Aggregate `tests`, `failures`, `errors`, and `skipped` from `apps/api/target/surefire-reports/TEST-*.xml`.

- [ ] **Step 4: Run `git diff --check` and a sensitive-field scan.**

Run: `git diff --check`

Run: `rg -n -i "prompt|input|output|credential|bearer|token|password" apps/api/src/main/java/com/huawei/skillcenter/release apps/web/src/QualityCenterView.jsx`

Expected: no sensitive payload field is introduced by the release control plane.
