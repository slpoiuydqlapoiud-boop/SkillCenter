# Execution Environment Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Agent Runtime、MCP Server 和 LLM Provider 纳入统一、可持久化、可审计且可被质量中心复用的执行环境资产目录。

**Architecture:** 在新的 `execution` 领域提供严格校验的 `ExecutionEnvironment` 模型、JSON 原子 Store 和管理员 Service/Controller。质量与 Runner 仅在创建新任务且环境 ID 非空时依赖 `requireActive`；历史查询保持兼容。Web 质量中心优先使用目录下拉选项，目录 API 不可用时回退到原有受控文本输入。

**Tech Stack:** Java 21, Spring Boot 3.4, Jackson, JUnit 5, MockMvc, React, Vite, Node test runner。

**Spec:** `docs/superpowers/specs/2026-08-24-execution-environment-catalog-design.md`

## Global Constraints

- 环境 ID 只允许 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`。
- 只保存空值或 `secret://...` 配置引用，不保存 endpoint、Token、Prompt、输入输出正文或工具参数。
- `ACTIVE` 才允许新评测/Runner 使用；`DEGRADED`、`DISABLED` 只允许查询历史。
- Provider `CONTRACT_ONLY` 与环境资产状态分离，不能因目录登记或连接探测变为生产就绪。
- 旧查询和空环境上下文保持兼容。
- 不重置或覆盖当前工作区中已有的用户改动，不创建提交。

---

### Task 1: Model and validation contract

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironmentKind.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironmentStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironment.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/execution/ExecutionEnvironmentTest.java`

- [x] **Step 1: Write the failing test** for valid assets, duplicate-safe identity fields, illegal IDs, raw credentials, empty version and invalid status.
- [x] **Step 2: Run the focused test** with `mvn.cmd -q -Dtest=ExecutionEnvironmentTest test`; confirm compilation or assertion failure is caused by missing model behavior.
- [x] **Step 3: Implement the enums and record** with bounded fields, normalized capabilities and safe configuration reference validation.
- [x] **Step 4: Re-run the focused test** and confirm all model cases pass.

### Task 2: Atomic catalog store

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironmentStore.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/execution/ExecutionEnvironmentStoreTest.java`

- [x] **Step 1: Write the failing test** for seeded defaults, deterministic listing, create conflict, status replacement, restart recovery and malformed snapshot rejection.
- [x] **Step 2: Run the focused test** and verify failure before production code.
- [x] **Step 3: Implement** an atomic JSON store using a `.tmp` replacement and a single business key `kind/environmentId`; when the file is absent seed exactly `openclaw/AGENT_RUNTIME`, `mcp-network/MCP_SERVER` and `llm-gateway/LLM_PROVIDER`, each as `context-v1`, `ACTIVE`, capability `context-only`, with no credential reference.
- [x] **Step 4: Re-run the focused store test** and verify restart and conflict behavior.

### Task 3: Admin service and API

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironmentCreateRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironmentStatusRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironmentService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/execution/ExecutionEnvironmentController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/execution/ExecutionEnvironmentControllerTest.java`

- [x] **Step 1: Write the failing MockMvc tests** for admin list/create/status, developer 403, duplicate conflict, safe response projection and audit events.
- [x] **Step 2: Run the focused controller test** and verify expected missing endpoint/model failures.
- [x] **Step 3: Implement** admin-only service/controller methods, stable `EXECUTION_ENVIRONMENT_*` errors, request IDs and audit metadata without secret fields.
- [x] **Step 4: Re-run the focused controller test** and confirm the complete API contract.

### Task 4: New-task status validation

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/SkillExecutionService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/execution/ExecutionEnvironmentUsageTest.java`

- [x] **Step 1: Write the failing test** proving ACTIVE environment IDs are accepted, DEGRADED/DISABLED IDs are rejected for new tasks, and empty context remains compatible.
- [x] **Step 2: Run the focused usage test** and confirm the services currently accept disabled/free-form IDs.
- [x] **Step 3: Inject `ExecutionEnvironmentService` through the Spring constructors**, preserve existing test constructors, and call `requireActive` only for nonblank context IDs.
- [x] **Step 4: Re-run focused quality/Runner tests** and verify historical list/query paths remain unaffected.

### Task 5: Web catalog selection

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/QualityCenterView.jsx`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/tests/operations-metrics.test.mjs`
- Test: `apps/web/tests/quality-center-view.test.mjs`

- [x] **Step 1: Write failing API/UI tests** for catalog path, Kind-filtered options, selected context propagation, status display and fallback when catalog API is absent.
- [x] **Step 2: Run focused Web tests** and confirm missing API method/controls fail.
- [x] **Step 3: Implement** `listExecutionEnvironments`, catalog loading, type-filtered selects and a clear “目录不可用时使用受控 ID” fallback.
- [x] **Step 4: Re-run focused Web tests** and verify existing environment propagation cases still pass.

### Task 6: Documentation and full verification

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`
- Modify: `docs/project/M11-external-integration-runbook.md`

- [x] **Step 1: Document** directory API, lifecycle semantics, compatibility behavior and the fact that catalog state does not equal external Provider readiness.
- [x] **Step 2: Run** `npm.cmd test` and `npm.cmd run build` in `apps/web`.
- [x] **Step 3: Run** `mvn.cmd -q test` in `apps/api` and count all Surefire tests.
- [x] **Step 4: Run** `git diff --check` and `pwsh -NoProfile -File scripts/verify-lifecycle.ps1 -SkipBuild -SkipSmoke`.
- [x] **Step 5: Record** verified counts and remaining external deployment gates; do not claim the long-term goal complete.
