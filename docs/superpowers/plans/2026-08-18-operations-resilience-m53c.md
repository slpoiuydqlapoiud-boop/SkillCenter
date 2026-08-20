# M5.3-C 运行韧性与发布门禁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 M5.3-B 增加可恢复指标存储、受保护 Prometheus 输出、内部阈值告警和 RC 自动门禁。

**Architecture:** `OperationsMetricsService` 继续负责指标聚合，通过 `OperationsMetricsStore` 接口隔离存储；生产默认使用原子替换的 JSON 文件存储，测试使用内存实现。Prometheus 控制器和告警服务只读取聚合快照，不接触请求原文；未来 Redis/外部告警通过适配接口替换。

**Tech Stack:** Java 21、Spring Boot 3.4、Jackson、JUnit 5、React 19、Node test runner、Vite。

**Spec:** `docs/superpowers/specs/2026-08-18-operations-resilience-m53c-design.md`

## Global Constraints

- 指标只保留最近 60 分钟聚合桶，不记录请求正文、Token、用户 ID、完整 URL 参数或包内容。
- 未配置 `skill-center.operations.metrics-token` 时 Prometheus 接口返回 404；不得记录 Token。
- 管理员接口复用 `ApiResponse`、requestId 和现有 Actor/RBAC 规则。
- 不新增 Redis、Prometheus Server、Grafana、Alertmanager 或 SSO 运行时依赖。
- 每个行为先写失败测试，再写最小实现；保留现有测试构造器兼容性。

---

### Task 1: 指标存储接口和 JSON 持久化

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/JsonOperationsMetricsStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsSnapshot.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/JsonOperationsMetricsStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsMetricsServiceTest.java`

**Interfaces:**
- `OperationsMetricsStore.load()` returns a list of serializable metric bucket records.
- `OperationsMetricsStore.save(List<OperationsMetricsService.PersistedBucket>)` persists the bounded bucket set.
- `OperationsMetricsService` keeps existing `recordRequest`, `recordSecurityEvent`, `snapshot`, and `clear` signatures.

- [ ] Step 1: Add a failing test that writes two buckets to a temporary file, creates a new store, loads them, and expects both timestamps and counters.
- [ ] Step 2: Run `mvn -B -q -f apps/api/pom.xml -Dtest=JsonOperationsMetricsStoreTest test`; expect failure because the store does not exist.
- [ ] Step 3: Implement the store with Jackson, parent-directory creation, temp-file write, and atomic move fallback; return empty data on missing file and throw a typed persistence exception only to the service boundary.
- [ ] Step 4: Add service wiring with `@Value("${skill-center.operations.metrics-storage:./data/operations/metrics.json}")`; production constructor uses JSON store, test constructor uses memory store; load only the last 60 buckets and save after every record.
- [ ] Step 5: Add health field `metricsPersistence` and values `ENABLED`, `DISABLED`, `DEGRADED`; do not expose file path.
- [ ] Step 6: Run focused store/service tests and confirm green.

### Task 2: Prometheus-compatible protected endpoint

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/PrometheusMetricsController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/MetricsTokenException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/PrometheusMetricsControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsReleaseGateTest.java`

**Interfaces:**
- `GET /internal/metrics` returns `text/plain; version=0.0.4` with fixed metric names.
- `PrometheusMetricsController` receives `OperationsMetricsService` and configured token; no actor headers are accepted as authentication.

- [ ] Step 1: Add tests for disabled 404, missing/wrong token 401, valid token 200, fixed metric names, and no sensitive field names.
- [ ] Step 2: Run the focused controller test and observe the expected failure.
- [ ] Step 3: Implement constant-time token comparison, explicit status responses, and deterministic exposition text using the 60-minute snapshot.
- [ ] Step 4: Add a release-gate assertion that valid output contains `skillcenter_requests_total`, `skillcenter_requests_server_errors_total`, `skillcenter_request_latency_p95_ms`, and `skillcenter_security_events_total`.
- [ ] Step 5: Run focused controller and gate tests.

### Task 3: Threshold alert evaluation and admin API

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertSnapshot.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/InvalidOperationsAlertQueryException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsAlertServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsAlertControllerTest.java`

**Interfaces:**
- `OperationsAlertService.evaluate(OperationsWindow)` returns `List<OperationsAlertSnapshot.Alert>`.
- `GET /api/v1/admin/operations/alerts?window=15m` returns `ApiResponse<List<OperationsAlertSnapshot.Alert>>`.
- Alert fields: `rule`, `status`, `currentValue`, `threshold`, `unit`, `firstTriggeredAt`, `lastEvaluatedAt`.

- [ ] Step 1: Add failing tests for P95 active/resolved, server-error minimum sample guard, security-event active, and non-admin/invalid-window responses.
- [ ] Step 2: Run focused service/controller tests and verify expected failure.
- [ ] Step 3: Implement configurable thresholds with defaults 1000ms, 0.05, and 10; use UTC clock injection for deterministic tests.
- [ ] Step 4: Implement active/resolved state transitions without retaining request-level data; stable sort by rule name.
- [ ] Step 5: Add controller and `400 INVALID_REQUEST` handler; run focused tests.

### Task 4: Frontend alert summary

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/operationsMetrics.js`
- Modify: `apps/web/src/OperationsMetricsView.jsx`
- Modify: `apps/web/src/operationsMetrics.css`
- Test: `apps/web/tests/operations-metrics.test.mjs`

**Interfaces:**
- `skillApi.getOperationsAlerts(window)` calls `/api/v1/admin/operations/alerts?window=...`.
- `normalizeOperationsAlerts(payload)` returns safe defaults with `alerts: []` and normalized status/value fields.

- [ ] Step 1: Add frontend tests for URL encoding and safe alert normalization; run them and verify red.
- [ ] Step 2: Implement API client and normalization helper.
- [ ] Step 3: Fetch alerts alongside metrics and render active/resolved summary with empty state; do not display token/path data.
- [ ] Step 4: Add responsive alert cards and status colors that also include text labels.
- [ ] Step 5: Run frontend focused tests and build.

### Task 5: RC gate, docs, and final verification

**Files:**
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsObservabilitySmokeTest.java`
- Create: `docs/project/M5.3-C-operations-resilience-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`

- [ ] Step 1: Extend smoke coverage for persistence health, Prometheus auth, alert API and sensitive-field absence.
- [ ] Step 2: Run `mvn -B -q -f apps/api/pom.xml test` and `npm.cmd test --prefix apps/web`.
- [ ] Step 3: Run `npm.cmd run build --prefix apps/web`.
- [ ] Step 4: Start API and Web servers and smoke-test normal API, protected metrics, admin alerts, viewer 403, and frontend proxy.
- [ ] Step 5: Write status document with delivered scope, limitations, configuration, and M5.3-D follow-up; update roadmap checkboxes.
- [ ] Step 6: Run an incomplete-marker scan on spec/plan/status and confirm all planned requirements are either implemented or explicitly bounded.
