# A3-1 Skill 生命周期关系投影实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持 JSON 为默认事实源的前提下，为 Skill、版本、发布、范围和版本关系建立可回滚的 PostgreSQL 生命周期关系投影与管理员查询控制面。

**Architecture:** 从现有 `GovernanceStore`、`ReleaseRecordStore`、`SkillScopeStore` 和 `SkillRelationStore` 读取已校验的 JSON 事实，规范化为不可变源快照并计算 SHA-256。显式管理员导入通过 PostgreSQL 事务一次性替换关系投影；查询只读投影，默认配置不创建新 DataSource、不双写、不在线切换。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Spring JDBC、Flyway、PostgreSQL JSON/default backends、Jackson、JUnit 5、AssertJ、MockMvc、Testcontainers PostgreSQL。

**Spec:** `docs/superpowers/specs/2026-08-25-skill-lifecycle-relational-projection-design.md`

## Global Constraints

- `skill-center.lifecycle-projection.backend` 只允许 `json` 或 `postgresql`，默认必须为 `json`。
- `postgresql` 投影必须同时满足全局 `skill-center.persistence.backend=postgresql`、A2 数据库配置有效和 Flyway V2 完成；失败时 fail-closed。
- JSON `GovernanceStore`、发布、范围和关系 Store 继续是本阶段事实源；不得运行时双写、自动回退或请求级切换。
- 导入只允许管理员显式调用，并要求 source SHA-256、actor 和 requestId；预检必须只读。
- 投影不得保存或返回 Prompt、输入输出、Trace 正文、工具参数、Token、凭据、制品路径或原始异常。
- 所有数据库 SQL 使用参数绑定；表名、列名、SQL、JDBC URL 不得来自请求。
- 导入在单个事务中完成，失败必须完整回滚；相同 source hash 幂等且不增加 revision。
- 共享工作区保持现状，不执行 reset、checkout、clean、commit、push 或删除既有用户产物。
- Docker 不可用时只允许命名的真实 PostgreSQL capability skip；编译、单元、JSON 兼容和 API 契约测试不得跳过。

---

### Task 1: Freeze canonical lifecycle snapshot contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionSnapshot.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionInput.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleSkillRow.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleVersionRow.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleReleaseRow.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleScopeRow.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleRelationRow.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionHasher.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionHasherTest.java`

**Interfaces:**
- `SkillLifecycleProjectionSnapshot(String sourceSha256, Instant sourceGeneratedAt, List<SkillLifecycleSkillRow> skills, List<SkillLifecycleVersionRow> versions, List<SkillLifecycleReleaseRow> releases, List<SkillLifecycleScopeRow> scopes, List<SkillLifecycleRelationRow> relations)` copies all lists and rejects blank hash/timestamps or null rows.
- `SkillLifecycleProjectionHasher.hash(SkillLifecycleProjectionInput input): String` sorts every row by its stable key, serializes fixed scalar fields only, and returns lowercase SHA-256.
- `SkillLifecycleSkillRow` contains `skillId`, `latestVersion`, `latestStatus`, `versionCount`, `publishedVersionCount`, `activeReleaseCount`, `visibility`, `ownerTeamId`, and `scopeRevision`.
- Version rows contain `skillId`, `version`, `packageId`, `status`, `sha256`, `sizeBytes`, `uploadedBy`, `uploadedAt`, `publishedAt`, and `riskLevel`; release rows contain only stable release/gate context; scope and relation rows follow the spec.

- [x] **Step 1: Write failing hash and immutable-contract tests.**

`sameFactsInDifferentCollectionOrderProduceTheSameHash` must assert the two fixture hashes are equal. `lifecycleContentChangeProducesDifferentHash` must assert a status change produces different hashes. `snapshotRejectsNullRowsAndBlankSourceMetadata` must construct a blank-hash snapshot and assert `IllegalArgumentException`.

- [x] **Step 2: Run the focused tests and verify RED.**

Run: `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 -Dtest=SkillLifecycleProjectionHasherTest test`

Expected: compilation failure because the lifecycle snapshot contracts and hasher do not yet exist.

- [x] **Step 3: Implement the minimal immutable rows, snapshot and canonical hasher.**

Use records with defensive `List.copyOf`, bounded stable identifiers, UTC `Instant` serialization, and explicit scalar field order. Do not serialize arbitrary Jackson objects or maps; this prevents JSON field-order and unknown metadata from changing the source hash.

- [x] **Step 4: Run the focused tests and verify GREEN.**

Run the same Maven command. Expected: all hash/order/immutability tests pass and no sensitive free-form fields are accepted in the canonical rows.

- [x] **Step 5: Record the contract in the SDD ledger.**

Append the focused RED/GREEN evidence to `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/progress.md`; do not edit unrelated ledgers.

### Task 2: Add projection schema and explicit backend wiring

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionBackendCondition.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionImportResult.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionRepository.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionConfiguration.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/PostgresSkillLifecycleProjectionStore.java`
- Create: `apps/api/src/main/resources/db/migration/V2__create_skill_lifecycle_projection.sql`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceConfiguration.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlProperties.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/PostgresSkillLifecycleProjectionStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceBackendConfigurationTest.java`

**Interfaces:**
- `SkillLifecycleProjectionRepository.status(): SkillLifecycleProjectionStatus`.
- `replace(SkillLifecycleProjectionSnapshot snapshot): SkillLifecycleProjectionImportResult` runs in the existing A2 transaction manager.
- `SkillLifecycleProjectionStatus` returns only backend, state, reasonCode, schemaVersion, revision, sourceSha256 and counts; it has redacted `toString()`.
- Task 2 creates only the PostgreSQL repository. JSON read-through is added with the source aggregation in Task 3; during Task 2 JSON must create neither a DataSource nor a PostgreSQL repository.

- [x] **Step 1: Write failing configuration, migration and repository contract tests.**

```java
@Test
void jsonProjectionDoesNotCreateDatabaseOrPostgresProjectionRepository() {
    new ApplicationContextRunner()
            .withUserConfiguration(PersistenceConfiguration.class)
            .withPropertyValues(
                    "skill-center.persistence.backend=json",
                    "skill-center.lifecycle-projection.backend=json")
            .run(context -> {
                assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
                assertThat(context.getBeansOfType(PostgresSkillLifecycleProjectionStore.class)).isEmpty();
            });
}

@Test
void migrationDefinesForeignKeysAndProjectionRevision() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V2__create_skill_lifecycle_projection.sql"));
    assertThat(sql).contains("skill_lifecycle_projection_meta")
            .contains("skill_lifecycle_skill_projection")
            .contains("skill_lifecycle_version_projection")
            .contains("FOREIGN KEY")
            .contains("revision bigint")
            .contains("INSERT INTO skill_lifecycle_projection_meta");
}
```

- [x] **Step 2: Run focused tests and verify RED.**

Run: `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 -Dtest=PersistenceBackendConfigurationTest,PostgresSkillLifecycleProjectionStoreTest test`

Expected: missing migration/repository/configuration failure, not a skipped assertion.

- [x] **Step 3: Add V2 schema and explicit conditional repository wiring.**

Create the six tables and indexes from the spec. Use A2 `JdbcTemplate`, `DataSourceTransactionManager`, and normalized backend conditions. Add `lifecycle-projection.backend: json` plus non-secret PostgreSQL selector documentation. Keep JSON startup free of DataSource creation.

- [x] **Step 4: Implement transactionally replacing PostgreSQL store.**

Lock the fixed meta row when present, reject `Long.MAX_VALUE` revision overflow, delete child rows before parent rows, insert parameter-bound rows in deterministic order, validate expected counts, then update/insert meta in one `TransactionTemplate`. Convert SQL/configuration failures to stable `FAIL_CLOSED`/`SKILL_LIFECYCLE_PROJECTION_IMPORT_FAILED` status without exception text.

- [x] **Step 5: Run focused configuration and repository tests.**

Run the focused commands from Steps 1–2 plus the existing A2 persistence tests. Expected: JSON context remains green; unit tests cover empty state, round-trip, repeat hash idempotency, revision increment, overflow and transaction rollback.

### Task 3: Build source aggregation, preflight and explicit import service

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionSource.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/JsonSkillLifecycleProjectionStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionQuery.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionView.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleImpactView.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionPreflight.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionSourceInvalidException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionServiceTest.java`

**Interfaces:**
- `SkillLifecycleProjectionSource.read(): SkillLifecycleProjectionInput` reads `GovernanceStore.snapshot()`, `ReleaseRecordStore.findAll(null, null, null, null)`, `SkillScopeStore.findAll()` and `SkillRelationStore.findAll(null, null, null, null, null)`.
- Missing scope uses the existing compatibility default `PUBLIC`, empty owner team and revision `0`; no scope row is fabricated in the source hash.
- `preflight(): SkillLifecycleProjectionPreflight` never calls `replace`.
- `importSnapshot(String expectedSourceSha256, Actor actor, String requestId): SkillLifecycleProjectionImportResult` compares the caller-supplied hash to the freshly read source, then calls the repository only when equal.
- `findSkills(SkillLifecycleProjectionQuery query, Actor actor)` and `findImpact(String skillId, String version, Actor actor)` enforce `SkillAuthorizationService` before returning views.

- [x] **Step 1: Write failing source, hash, preflight and authorization tests.**

Add these exact behaviors to `SkillLifecycleProjectionServiceTest`: `preflightReadsAllFourJsonFactsWithoutChangingProjection` asserts all four source stores are represented and repository revision is unchanged; `importRejectsStaleExpectedHashBeforeDatabaseMutation` asserts stable `SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED`; `identicalSourceHashIsIdempotent` asserts the second import returns `idempotent=true` with unchanged revision; `hiddenSkillImpactIsNotReturnedToUnauthorizedActor` asserts the existing authorization exception; `malformedRelationTargetFailsWithStableSourceInvalidCode` asserts `SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID` without source content in the message.

Assertions must inspect the repository call/result and stable error code, never mock invocation count alone as the sole proof of behavior.

- [x] **Step 2: Run service tests and verify RED.**

Run: `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 -Dtest=SkillLifecycleProjectionServiceTest test`

Expected: compile failure for missing source/service contracts.

- [x] **Step 3: Implement source aggregation and normalization.**

Build versions keyed by `(skillId, version)`; reject duplicates, missing IDs, invalid hashes or contradictory status fields. Derive latest version deterministically by `uploadedAt`, version and package ID. Compute published and active-release counts. Validate scope/relations through existing stores and preserve the default PUBLIC compatibility behavior.

- [x] **Step 4: Implement preflight/import and safe views.**

Preflight returns source hash, counts, current hash comparison and stable difference code. Import requires hash equality, actor and requestId; same hash returns an idempotent result. Views expose counts, status, environment and stable release/relationship IDs only after authorization.

- [x] **Step 5: Run service tests and existing authorization regressions.**

Run the focused service test, `SkillAuthorizationBoundaryTest`, `SkillRelationServiceTest` and release admission tests. Expected: hidden resources remain hidden and source failures are fail-closed.

### Task 4: Add administrator control-plane API and lifecycle query surface

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionImportRequest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/api/ApiErrorContractTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/api/SensitiveResponseContractTest.java`

**Interfaces:**
- `GET /api/v1/admin/skill-lifecycle/projection/status`
- `POST /api/v1/admin/skill-lifecycle/projection/preflight`
- `POST /api/v1/admin/skill-lifecycle/projection/import` with `{ "sourceSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }`; actor comes only from `ActorResolver`, requestId from the HTTP request.
- `GET /api/v1/admin/skill-lifecycle/projection/skills?skillId=&version=&status=&environment=&page=&pageSize=`
- `GET /api/v1/admin/skill-lifecycle/projection/skills/{skillId}/impact?version=`

- [x] **Step 1: Write failing MockMvc authorization and contract tests.**

Assert admin success, non-admin `403`, preflight has no repository mutation, stale import returns stable `SKILL_LIFECYCLE_PROJECTION_SOURCE_CHANGED`, malformed IDs return the existing validation envelope, and responses contain no path/Prompt/Trace/token/raw exception fields.

- [x] **Step 2: Run focused controller tests and verify RED.**

Run: `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 -Dtest=SkillLifecycleProjectionControllerTest,ApiErrorContractTest,SensitiveResponseContractTest test`

Expected: missing controller routes or handlers cause failures.

- [x] **Step 3: Implement explicit admin routes and stable error mapping.**

Use existing actor/role guards and request ID extraction. Keep import response metadata-only. Do not accept actor, owner team, maintainer list, SQL or database settings from request JSON.

- [x] **Step 4: Run focused controller and security tests.**

Run the focused command plus access-boundary regressions. Expected: no authorization bypass and no sensitive field leakage.

### Task 5: Documentation, verifier and independent review

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`
- Create: `scripts/verify-skill-lifecycle-projection.ps1`
- Create: `scripts/verify-skill-lifecycle-projection.Tests.ps1`
- Create: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/progress.md`
- Create: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/task-5-report.md`
- Create: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/task-5-review-package.md`
- Create: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/task-5-review.md`

- [x] **Step 1: Write verifier contract tests.**

The verifier must run canonical/hash/service/controller focused API tests, existing A2 persistence tests, Web tests/build and `git diff --check`; it must check JSON default, migration V2, source-hash idempotency, API error contracts and reject destructive commands. Surefire evidence must be phase-fresh and Docker skips must be explicit only for the named PostgreSQL integration suite.

- [x] **Step 2: Add runbook and lifecycle status boundaries.**

Document offline preflight/import, source hash approval, rollback by retaining JSON as the source, PostgreSQL provisioning prerequisite, no dual-write, no database backup/PITR claim, authorization boundaries and remaining domain write migration work.

- [x] **Step 3: Run final verification.**

```powershell
Invoke-Pester -Script .\scripts\verify-skill-lifecycle-projection.Tests.ps1 -PassThru
mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=SkillLifecycleProjectionHasherTest,PostgresSkillLifecycleProjectionStoreTest,SkillLifecycleProjectionServiceTest,SkillLifecycleProjectionControllerTest,PersistenceBackendConfigurationTest,ApiErrorContractTest,SensitiveResponseContractTest" test
mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 test
Push-Location apps/web
npm.cmd test
npm.cmd run build
Pop-Location
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-skill-lifecycle-projection.ps1
git diff --check
```

- [x] **Step 4: Complete independent read-only review.**

Review against the A3-1 spec and this plan. Confirm transaction rollback, stable hash/revision, JSON compatibility, authorization, sensitive-data boundaries, explicit capability skips and no production-readiness overclaim. The reviewer may only write `task-5-review.md`.

- [x] **Step 5: Update roadmap and ledger.**

Record A3-1 Phase 1 completion and leave PostgreSQL production provisioning, database backup/PITR, runtime write migration, SSO/JWT, Redis HA and real Provider readiness explicitly open.
