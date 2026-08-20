# M5.3-B 运行可观测性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本项目无 Git 仓库，不执行 commit、reset 或 branch 命令。

**Goal:** 为内部 Skill 平台增加进程内运行指标、安全事件计数、管理员查询 API 和管理员运行监控页。

**Architecture:** 用 `OperationsMetricsService` 按 1 分钟固定桶保存最近 60 分钟聚合数据；`RequestMetricsFilter` 包裹 API 请求采集状态和延迟，现有安全过滤器和异常处理器只上报安全事件，不保存敏感值。`OperationsMetricsController` 通过 `ActorResolver` 限制 admin，并返回统一 `ApiResponse`。

**Tech Stack:** Java 21、Spring Boot 3.4、Servlet Filter、ConcurrentHashMap/AtomicLongArray、JUnit 5、MockMvc、React、Node test runner。

**Spec:** `docs/superpowers/specs/2026-08-18-operations-observability-m53b-design.md`

## Global Constraints

- 只采集聚合指标，不记录请求体、响应体、Token、用户 ID、Origin 值、查询参数或完整 URL。
- 指标使用固定 1 分钟桶，最多保留最近 60 分钟，过期桶自动清理。
- `GET /api/v1/admin/operations/metrics` 只允许 `admin`；非法 `window` 返回 `400 INVALID_REQUEST`。
- 不新增 Actuator、Micrometer、Redis、Prometheus、Grafana 或外部告警依赖。
- Skill 仍然只能从本地 ZIP 上传，不增加在线创建、编辑或草稿流程。

---

### Task 1：指标模型与聚合服务

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsSnapshot.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsWindow.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsMetricsServiceTest.java`

**Interfaces:**
- `OperationsWindow.parse(String)` returns `OperationsWindow` for `5m`, `15m`, `60m`, otherwise throws `IllegalArgumentException`.
- `OperationsMetricsService.recordRequest(int status, long durationMs)` records one request.
- `OperationsMetricsService.recordSecurityEvent(String eventCode)` increments one event code.
- `OperationsMetricsService.snapshot(OperationsWindow window)` returns `OperationsMetricsSnapshot`.
- `OperationsMetricsService.clear()` clears buckets for tests.

- [ ] **Step 1: Write the failing test**

  Cover valid/invalid window parsing, request status aggregation, approximate P50/P95/max calculation, security event counts, expiry of old buckets with a mutable `Clock`, and safe behavior for negative latency.

- [ ] **Step 2: Run test to verify it fails**

  Run: `mvn -B -q -f apps/api/pom.xml "-Dtest=OperationsMetricsServiceTest" test`

  Expected: FAIL because operations metrics classes do not exist.

- [ ] **Step 3: Write minimal implementation**

  Implement immutable records for response data, a parser with only three accepted windows, and a thread-safe fixed-minute bucket service. Use `AtomicLongArray` for the eight latency buckets and remove buckets older than 60 minutes during record/snapshot.

- [ ] **Step 4: Run focused test**

  Run: `mvn -B -q -f apps/api/pom.xml "-Dtest=OperationsMetricsServiceTest" test`

  Expected: PASS.

---

### Task 2：API 请求与安全事件采集

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/RequestMetricsFilter.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/security/RateLimitFilter.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/security/OriginGuardFilter.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/RequestMetricsFilterTest.java`

**Interfaces:**
- `RequestMetricsFilter` records only `/api/` requests and always delegates the chain before recording the final response status.
- Security filters call `OperationsMetricsService.recordSecurityEvent(...)` only on rejection.
- `GlobalExceptionHandler` records only the five security event codes defined by the spec.

- [ ] **Step 1: Write the failing test**

  Test that the filter records status and latency while preserving downstream status, ignores non-API requests, and that rate-limit/origin rejection paths increment their corresponding security event.

- [ ] **Step 2: Run test to verify it fails**

  Run: `mvn -B -q -f apps/api/pom.xml "-Dtest=RequestMetricsFilterTest" test`

  Expected: FAIL because the request metrics filter and event hooks are absent.

- [ ] **Step 3: Write minimal implementation**

  Add an ordered `OncePerRequestFilter` after the existing security filters, capture `System.nanoTime()` around the chain, and call the metrics service with final status and non-negative elapsed milliseconds. Add event calls at the existing rejection branches without logging request values.

- [ ] **Step 4: Run focused and adjacent tests**

  Run: `mvn -B -q -f apps/api/pom.xml "-Dtest=RequestMetricsFilterTest,RateLimitFilterTest,OriginGuardFilterTest,ApiErrorContractTest" test`

  Expected: PASS.

---

### Task 3：管理员运行指标 API

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsMetricsControllerTest.java`

**Interfaces:**
- `GET /api/v1/admin/operations/metrics?window=5m|15m|60m` returns `ApiResponse<OperationsMetricsSnapshot>`.
- Admin access is enforced with `ActorResolver`; non-admin throws `ForbiddenException` with existing `FORBIDDEN` contract.
- Invalid `window` maps to `400 INVALID_REQUEST` without exposing stack traces or internal state.

- [ ] **Step 1: Write the failing test**

  Use MockMvc to verify admin receives the metrics envelope, viewer receives 403, invalid window receives 400, and the response contains no user ID, request body, Origin, or token fields.

- [ ] **Step 2: Run test to verify it fails**

  Run: `mvn -B -q -f apps/api/pom.xml "-Dtest=OperationsMetricsControllerTest" test`

  Expected: FAIL because the controller route is absent.

- [ ] **Step 3: Write minimal implementation**

  Resolve the actor once, reject any role other than `admin`, parse the window, call `OperationsMetricsService.snapshot`, and return request ID through `ApiResponse`.

- [ ] **Step 4: Run focused test**

  Run: `mvn -B -q -f apps/api/pom.xml "-Dtest=OperationsMetricsControllerTest" test`

  Expected: PASS.

---

### Task 4：前端运行监控页

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/state.js`
- Create: `apps/web/src/OperationsMetricsView.jsx`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/tests/api-client.test.mjs`
- Test: `apps/web/tests/operations-metrics.test.mjs`

**Interfaces:**
- `skillApi.getOperationsMetrics(window = "15m")` calls `/api/v1/admin/operations/metrics?window=...`.
- `OperationsMetricsView` accepts `{ api, role }`, loads the selected window, and renders health, request, latency and security event summaries.
- `ROLES.admin.navigation` includes `operations`; other roles do not.

- [ ] **Step 1: Write the failing tests**

  Add API client path coverage and view helper/render contract coverage for window selection, admin-only navigation, empty metrics and error display.

- [ ] **Step 2: Run test to verify it fails**

  Run: `npm.cmd test --prefix apps/web -- --test-name-pattern="operations metrics|operations navigation"`

  Expected: FAIL because the API method, view and navigation entry are absent.

- [ ] **Step 3: Write minimal implementation**

  Add the API method, admin-only navigation item, route branch in `App.jsx`, and a compact responsive panel that uses only returned aggregate fields. Keep direct 403 errors visible through the existing `ApiError` message mapping.

- [ ] **Step 4: Run frontend tests and build**

  Run: `npm.cmd test --prefix apps/web`; `npm.cmd run build --prefix apps/web`

  Expected: PASS and production build succeeds.

---

### Task 5：文档、冒烟联调与全量验证

**Files:**
- Create: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsObservabilitySmokeTest.java`
- Create: `docs/project/M5.3-B-operations-observability-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`

- [ ] **Step 1: Write the failing smoke test**

  Cover a normal API request followed by admin metrics query, one Origin rejection and one rate-limit rejection, asserting the metrics response changes while no sensitive field appears.

- [ ] **Step 2: Run smoke test to verify missing integration**

  Run: `mvn -B -q -f apps/api/pom.xml "-Dtest=OperationsObservabilitySmokeTest" test`

  Expected: FAIL until the complete filter/controller integration exists.

- [ ] **Step 3: Write status documentation and roadmap update**

  Record the delivered metrics contract, process-local limitation, admin-only access, tested commands and the remaining M5.3-C external monitoring/multi-instance work.

- [ ] **Step 4: Run full verification**

  Run: `mvn -B -q -f apps/api/pom.xml test`; `npm.cmd test --prefix apps/web`; `npm.cmd run build --prefix apps/web`.

  Expected: all backend/frontend tests and production build pass.

- [ ] **Step 5: Run real API smoke**

  With API on `8080` and Web on `5173`, verify market `200`, admin metrics `200`, viewer metrics `403`, and metrics fields remain aggregate-only.
