# Operations Alert State Persistence Implementation Plan

> **For agentic workers:** This plan is implemented inline in the current session. Steps use checkbox syntax for tracking.

**Goal:** 为 Operations Alert 增加可持久化、可跨实例去重且可纳入生产 Readiness 的告警状态仓库。

**Architecture:** 将现有 `OperationsAlertService` 的进程内 `Map` 替换为 `OperationsAlertStateRepository`。memory 实现保持默认开发行为；Redis 实现使用固定 Hash 与 Lua 原子迁移，任何 Redis 失败都不回退 memory。Readiness 通过可选健康端口展示 backend 状态，Operations Center 复用现有通用组件列表。

**Tech Stack:** Java 21, Spring Boot, Spring Data Redis `StringRedisTemplate`, JUnit 5, Mockito, Maven, React/Vite 现有 readiness UI。

**Spec:** `docs/superpowers/specs/2026-08-25-operations-alert-state-design.md`

## Global Constraints

- 默认 `skill-center.operations.alert-state-backend=memory`，不改变本地开发模式。
- 显式 Redis 模式不允许自动回退 memory。
- Redis 状态迁移必须单次 Lua 脚本完成读取、计算和写入。
- 状态和 Readiness 响应不得包含 endpoint、凭据、Prompt、Trace、请求正文或原始异常。
- 生产能力声明必须以 fresh API/Web/lifecycle 验证为依据；Docker capability skip 不视为真实 HA 验收。

---

### Task 1: Define the state transition contract

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertState.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertStateTransition.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertStateRepository.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertStateReadiness.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertStateHealth.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsAlertStateRepositoryTest.java`

**Interfaces:**
- `OperationsAlertStateRepository.transition(String key, boolean active, Instant evaluatedAt)` returns `OperationsAlertStateTransition`.
- `OperationsAlertStateTransition.previous()` may be null; `current()` is never null; `transitioned()` indicates active-edge change.
- `OperationsAlertStateHealth.readiness()` returns `OperationsAlertStateReadiness`.

- [ ] Write tests for first ACTIVE, repeated ACTIVE, ACTIVE→RESOLVED, first RESOLVED, and repeated RESOLVED.
- [ ] Run `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=OperationsAlertStateRepositoryTest" test`; expected RED because the contract types do not exist.
- [ ] Implement immutable records with normalized safe fields and the repository/health interfaces.
- [ ] Run the focused test again; expected GREEN for the transition contract once memory implementation is supplied in Task 2.

### Task 2: Add the memory repository and migrate OperationsAlertService

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/MemoryOperationsAlertStateRepository.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertService.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsAlertServiceTest.java`

**Interfaces:**
- Memory repository synchronizes `transition` and returns `OPERATIONS_ALERT_STATE_MEMORY_ONLY` with `DEGRADED` readiness.
- `OperationsAlertService` calls the repository for every alert key and notifies only on the transition result.
- Existing convenience constructors delegate to memory repository; Spring constructor receives the configured repository bean.

- [ ] Add a service test proving repeated evaluations notify once and RESOLVED notifies once after restart-compatible state injection.
- [ ] Run `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=OperationsAlertServiceTest" test`; expected RED until service uses the repository.
- [ ] Implement memory transition semantics and service constructor delegation.
- [ ] Run the focused OperationsAlert tests; expected GREEN with existing notification assertions unchanged.

### Task 3: Add the explicit Redis repository

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/RedisOperationsAlertStateRepository.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/operations/RedisOperationsAlertStateRepositoryTest.java`
- Modify: `apps/api/src/main/resources/application.yml`

**Interfaces:**
- Conditional bean when `skill-center.operations.alert-state-backend=redis`.
- Uses `skill-center.operations.alerts.state-key` as a fixed key prefix.
- Lua result contains previous-active marker, transition marker, first-trigger epoch, and evaluated epoch; no dynamic table/key names.

- [ ] Add tests for atomic script invocation, PONG readiness, null/non-PONG readiness, and exception redaction.
- [ ] Run the Redis focused tests; expected RED because the repository and configuration do not exist.
- [ ] Implement fixed-key Lua transition and metadata-only PING readiness without memory fallback.
- [ ] Run Redis focused tests; expected GREEN.

### Task 4: Integrate Readiness and configuration boundaries

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/PlatformReadinessService.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/operations/PlatformReadinessServiceTest.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/operations/PlatformReadinessControllerTest.java`

**Interfaces:**
- Add optional `OperationsAlertStateHealth` to the readiness aggregation.
- Component ID is `OPERATIONS_ALERT_STATE`; statuses are only `READY`, `DEGRADED`, or `NOT_READY`.

- [ ] Add tests for memory degraded, Redis ready, and Redis unavailable blocking signals.
- [ ] Run focused readiness tests; expected RED until the component is wired.
- [ ] Wire the optional health bean and preserve all existing constructors used by focused tests.
- [ ] Run focused readiness and API error-contract tests; expected GREEN.

### Task 5: Document and verify the lifecycle increment

**Files:**
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`

- [ ] Document backend selection, restart recovery, Redis failure behavior, and external HA/DR acceptance.
- [ ] Run `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 test` and parse fresh Surefire totals.
- [ ] Run `npm.cmd test` and `npm.cmd run build` in `apps/web`.
- [ ] Run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-skill-lifecycle-projection.ps1`.
- [ ] Run `git diff --check` and recalculate roadmap completion from checked items.
