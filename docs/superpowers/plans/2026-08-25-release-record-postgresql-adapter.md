# ReleaseRecord PostgreSQL Transaction Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 JSON 默认行为的前提下，为 ReleaseRecord 提供可显式启用的 PostgreSQL 事务适配器。

**Architecture:** 以 `ReleaseRecordRepository` 作为发布域端口；JSON store 和 JDBC store 实现同一查询/写入契约。PostgreSQL 通过 Flyway 迁移建立带唯一约束的发布记录表，selector 仅在全局 PostgreSQL persistence 已启用时生效；不双写、不回退，失败时 fail-closed。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Spring JDBC、Flyway、PostgreSQL、Jackson JSONB、JUnit 5、Mockito、Testcontainers PostgreSQL。

**Spec:** `docs/superpowers/specs/2026-08-25-release-record-postgresql-adapter-design.md`

## Global Constraints

- `skill-center.release-backend` 默认必须为 `json`，只允许 `json` 或 `postgresql`。
- `release-backend=postgresql` 必须同时要求 `persistence.backend=postgresql`；不满足时 fail-closed，不自动切回 JSON。
- SQL 必须使用参数绑定；响应、日志和审计不得包含 JDBC URL、密码、SQL、Prompt、输入输出、Trace 正文或原始异常。
- `ReleaseRecord` 的状态机和不可变上下文约束必须由 JSON/JDBC 两个实现共同遵守。
- 同一 `release_id`、`idempotency_key` 不得重复；同一 Skill/版本/目标环境不得存在多个非终态发布批次。
- 共享工作区不执行 reset、checkout、clean、commit、push 或删除既有用户产物。

## 文件边界

- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseRecordRepository.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/JdbcReleaseRecordStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseRecordStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseAdmissionService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionSource.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionConfiguration.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlProperties.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactCatalog.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceConfiguration.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/main/resources/db/migration/V4__create_release_records.sql`
- Create/modify tests under `apps/api/src/test/java/com/huawei/skillcenter/release`, `persistence`, `lifecycle`, and `relationship`
- Modify: `apps/api/pom.xml` not expected; existing Spring JDBC, Flyway, PostgreSQL driver, and Testcontainers dependencies are sufficient
- Modify: `docs/project/M11-external-integration-runbook.md`, `docs/project/remaining-coding-tasks-status.md`, and the SDD progress ledger

### Task 1: Freeze the repository port and JSON compatibility

**Interfaces:** `ReleaseRecordRepository` exposes `findAll`, `find`, `findByIdempotencyKey`, `findActiveBusinessKey`, `create`, and `replace` with the same parameter/return types as the current store.

- [x] Write a failing contract test that a JSON `ReleaseRecordStore` can be assigned to `ReleaseRecordRepository`, and that all current create/replace/query invariants remain enforced.
- [x] Run `mvn -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=ReleaseRecordStoreTest,ReleaseServiceTest,ReleaseAdmissionServiceTest" test`; observed the expected missing-port compilation failure.
- [x] Add the interface, make the JSON store implement it, add JSON conditional wiring with `matchIfMissing=true`, and preserve direct test constructors.
- [x] Change only production consumers that currently require the concrete type to depend on the interface; update test mocks/types where Java requires the new port.
- [x] Re-run the focused release/lifecycle/relationship tests and verify all pass before adding JDBC behavior.

### Task 2: Add selector validation, Flyway schema, and persistence artifact semantics

**Interfaces:** `PersistenceControlProperties.normalizedReleaseBackend()` returns a safe normalized selector; the persistence catalog reports `releases` as PostgreSQL only when that selector is active.

- [x] Write failing tests for JSON default, invalid selector, PostgreSQL selector without global PostgreSQL backend, schema contents, and JSON mode creating no JDBC bean.
- [x] Run the focused configuration tests and record the expected RED compilation failure.
- [x] Add `release-backend` configuration and validation, plus `V4__create_release_records.sql` with columns for every `ReleaseRecord` field, JSONB `gate_snapshot`, and unique/partial indexes for release identity, idempotency, and non-terminal business keys.
- [x] Update conditional backend wiring so `JdbcReleaseRecordStore` is created only for an explicit valid PostgreSQL selector; do not alter existing quality-evidence conditions.
- [x] Update artifact catalog/control/readiness logic so PostgreSQL release storage is not checked as a JSON file and the artifact is excluded from A1 JSON snapshot with a stable unsupported response.
- [x] Re-run persistence startup, catalog, migration, and JSON Spring smoke tests.

### Task 3: Implement transactional JDBC repository

**Interfaces:** `JdbcReleaseRecordStore` implements `ReleaseRecordRepository` and accepts `JdbcTemplate`, `ObjectMapper`, and `PlatformTransactionManager`.

- [x] Write failing repository tests for empty reads, round-trip create/find, idempotency conflict, active business-key conflict, immutable-context replacement rejection, valid state replacement, and restart-equivalent reads.
- [x] Run the focused JDBC test; observed the expected missing-class compilation failure.
- [x] Implement parameterized SQL and row mapping. Serialize/deserialize only the safe `ReleaseGateSnapshot` JSONB document; run existing `ReleaseRecord` constructor validation on every loaded/written record.
- [x] Wrap create and replace in transactions. Use database unique constraints as the final concurrency guard and translate duplicate/constraint failures to the existing stable `ReleaseConflictException` family.
- [x] Add an integration test using PostgreSQL Testcontainers for round-trip and conflict behavior; Docker is unavailable, so the test reports only the named capability skip.
- [x] Run JSON and JDBC focused suites together; compilation and non-Docker release tests pass with the integration test skipped by capability.

### Task 4: Wire service behavior and regression boundaries

- [x] Add a Spring context test proving JSON mode has exactly one `ReleaseRecordRepository` bean and no JDBC store; add a PostgreSQL selector test proving the concrete JDBC store is selected only under the dual opt-in.
- [x] Verify `ReleaseService`, `ReleaseAdmissionService`, lifecycle projection source, and relation impact queries compile and preserve existing behavior through their interface dependency.
- [x] Add redaction/error contract assertions for database unavailable, schema mismatch, and persistence failure; assert no cause text or credentials escape stable errors through the existing stable persistence error family and API boundary tests.
- [x] Run release, admission, lifecycle projection, relation, persistence, and API error focused tests.

### Task 5: Document and verify the complete increment

- [x] Document the selector, migration order, fail-closed behavior, no-dual-write rule, snapshot limitation, and external database acceptance evidence in the M11 runbook.
- [x] Mark only the ReleaseRecord persistence item complete in the project status/SDD ledger; leave real production database supply, capacity/SLO, backup/PITR and UAT explicitly open.
- [x] Run `mvn -q -f apps/api/pom.xml -DforkCount=0 test` and parse Surefire reports for tests/failures/errors/skips.
- [x] Run `npm test` and `npm run build` in `apps/web`.
- [x] Run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-skill-lifecycle-projection.ps1` and `git diff --check`.
- [x] Record exact final counts and the Docker capability skip reason in the SDD progress ledger.
