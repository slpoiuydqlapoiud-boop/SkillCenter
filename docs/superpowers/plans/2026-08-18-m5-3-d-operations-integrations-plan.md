# M5.3-D Operations Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or **superpowers:executing-plans** to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为内部 Skill 平台补齐 M5.3-D 的可配置多实例指标聚合、外部告警通知适配和一致性/容量验证，同时保持本地 JSON 模式兼容。

**Architecture:** `OperationsMetricsStore` 增加按桶增量合并和共享读取能力；本地 JSON 存储继续作为默认实现，Redis 实现使用哈希桶和 Lua 原子增量以避免多实例覆盖。告警服务通过 `OperationsAlertNotificationSink` 解耦通知渠道，默认空实现，配置 URL 后启用异步 HTTP Webhook；所有通知只发送聚合告警字段，不发送请求正文或凭据。

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Data Redis, Jackson, JUnit 5, AssertJ, Maven。

**Spec:** `docs/project/M5.3-C-operations-resilience-status.md` and `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`

## Global Constraints

- 默认 `skill-center.operations.metrics-storage` 继续使用本地 JSON；只有显式配置 `redis` 才连接 Redis。
- 指标只保留聚合桶，不记录 prompt、output、文件内容、Token、用户 ID 或完整 URL 参数。
- Redis 合并必须是单桶原子操作；Redis 不可用时本地 API 不得因通知/监控写入崩溃，并返回 `DEGRADED` 健康状态。
- 告警通知只在 `RESOLVED ↔ ACTIVE` 状态转换时发送，重复轮询不得重复发送。
- 所有新增行为先写失败测试，确认失败后再实现；最终必须通过后端全量测试和前端回归测试。

---

### Task 1: 增量指标存储契约与 JSON 合并

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/JsonOperationsMetricsStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/JsonOperationsMetricsStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsMetricsServiceTest.java`

**Interfaces:**
- `OperationsMetricsStore.merge(List<Bucket>)` adds counters, latency buckets and security events, taking the maximum `maxMs`.
- `OperationsMetricsStore.sharedReads()` defaults to `false`; centralized implementations return `true`.

- [x] Write a failing test proving two JSON store merges add counts instead of replacing the first bucket.
- [x] Run `mvn -q -f apps/api/pom.xml -Dtest=JsonOperationsMetricsStoreTest test` and confirm the new assertion fails before implementation.
- [x] Add the merge/shared-read contract and implement synchronized JSON read/merge/write with existing atomic replacement.
- [x] Update `OperationsMetricsService` to persist event deltas through `merge`, use shared store reads for snapshots, and keep `clear()` replacement semantics.
- [x] Run the focused tests and then the operations package tests; confirm all pass.

### Task 2: Redis 多实例指标聚合适配器

**Files:**
- Modify: `apps/api/pom.xml`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/RedisOperationsMetricsStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsMetricsService.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/RedisOperationsMetricsStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsMetricsServiceTest.java`

**Interfaces:**
- `RedisOperationsMetricsStore` stores one Redis hash per time bucket and a bucket index set.
- A Lua script atomically increments counters, updates max latency, records security events, and applies TTL.
- Configuration: `skill-center.operations.metrics-storage=redis`, `skill-center.operations.redis.key`, `skill-center.operations.redis.ttl-seconds`.

- [x] Write a failing fake-Redis test proving concurrent merges preserve totals, histogram counts and security events.
- [x] Run the focused test and confirm failure because the adapter is absent.
- [x] Add the Spring Data Redis dependency and implement the hash/index store with guarded error status and clear semantics.
- [x] Select the adapter only for explicit `redis` storage mode; retain memory/JSON constructors used by existing tests.
- [x] Add a shared-store service test with two service instances and a bounded concurrent load test for 20,000 events.
- [x] Run Redis adapter and service tests without requiring a live Redis server.

### Task 3: 外部告警通知适配与状态转换

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertNotificationSink.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/NoopOperationsAlertNotificationSink.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/WebhookOperationsAlertNotificationSink.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertNotificationConfiguration.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/OperationsAlertService.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsAlertServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/WebhookOperationsAlertNotificationSinkTest.java`

**Interfaces:**
- `OperationsAlertNotificationSink.notify(OperationsAlertSnapshot)` is fire-and-forget from the alert service.
- The Webhook sink posts a JSON envelope to a configured URL and swallows transport failures; no notification failure changes alert API status.
- `skill-center.operations.alerts.notification-url` blank/unset selects the no-op sink.

- [x] Write failing tests for first ACTIVE, repeated ACTIVE, and ACTIVE→RESOLVED notification counts.
- [x] Run the focused alert tests and confirm the transition assertions fail.
- [x] Add sink selection, state-transition notification, and async HTTP JSON webhook implementation with timeout configuration.
- [x] Add a transport-injected webhook test proving payload fields are aggregate-only and secrets are absent.
- [x] Run the focused tests and operations controller smoke tests.

### Task 4: 文档、配置契约与发布验证

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `docs/project/M5.3-C-operations-resilience-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/OperationsReleaseGateTest.java`

- [x] Extend the RC gate with shared-store aggregation and notification transition coverage using test doubles.
- [x] Run Maven backend tests, frontend tests and frontend production build from clean commands.
- [x] Review the diff for secrets, sensitive metric labels, accidental default Redis activation and API compatibility.
- [x] Document M5.3-D delivered scope, configuration examples, degraded behavior, and the remaining external deployment work.
- [x] Mark only the M5.3-D coding items complete; keep real infrastructure rollout and M6 production gates explicitly open.
