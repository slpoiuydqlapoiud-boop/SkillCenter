# A2 PostgreSQL/Flyway 与 Quality Evidence 适配器实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 JSON 默认行为的前提下，为 Quality Evidence 建立可由 PostgreSQL/Flyway 驱动的真实事务持久化适配器，并接入 A1 启动状态与快照边界。

**Architecture:** 使用现有 `QualityEvidenceRepository` 作为领域端口，新增显式的 `PersistenceBackend` 状态端口和条件化 PostgreSQL 配置。PostgreSQL 以单个 JSONB 聚合文档保存 `QualityEvidenceState`，由 Java 复用现有完整恢复校验，数据库提供事务、revision 和锁定；JSON 与 PostgreSQL 不双写、不自动回退。A1 文件快照在 PostgreSQL 质量证据启用时明确返回 unsupported，避免伪造数据库备份。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Spring JDBC、HikariCP、Flyway、PostgreSQL、Jackson、JUnit 5、AssertJ、Mockito、Testcontainers PostgreSQL（环境具备 Docker 时运行，其他环境显式 capability skip）。

**Spec:** `docs/superpowers/specs/2026-08-25-postgresql-quality-evidence-adapter-design.md`

## Global Constraints

- `skill-center.persistence.backend` 只允许 `json` 或 `postgresql`；默认必须是 `json`。
- `skill-center.quality-evidence-backend` 只允许 `json` 或 `postgresql`；未配置时必须是 `json`。
- PostgreSQL 模式连接失败、Flyway 未完成、未知 schema 或 wiring 缺失必须是 `FAIL_CLOSED`，不得回退 JSON。
- PostgreSQL 密码、JDBC URL 中的凭据、SQL、Prompt、输入输出、Trace 正文、工具参数、Token、凭据和原始异常 cause 不得出现在状态 API、日志或稳定错误响应中。
- 所有数据库 SQL 使用参数绑定；业务 Controller 不得直接依赖 `JdbcTemplate` 或 Flyway。
- Quality Evidence 的 `load/save/update/clear` 必须复用 `QualityEvidenceRepository`，并保留现有 Java 领域完整校验。
- JSON 默认路径、现有 JSON 构造器、质量服务、A1 metadata-only API 和 offline restore-preflight 行为必须保持兼容。
- 本阶段不实现运行时双写、在线数据库切换、数据库覆盖恢复、GovernanceStore 全量关系化或生产 Provider 接入。
- 共享工作区保持现状，不执行 reset、checkout、clean、commit、push 或删除既有用户产物。

## File Map

- Create `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceBackend.java`: backend status port.
- Create `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceBackendStatus.java`: redacted immutable status value.
- Create `apps/api/src/main/java/com/huawei/skillcenter/persistence/JsonPersistenceBackend.java`: JSON backend status implementation.
- Create `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceBackend.java`: read-only database/Flyway status implementation.
- Create `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceProperties.java`: bound and validated non-secret connection settings.
- Create `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceConfiguration.java`: conditional DataSource, JdbcTemplate, transaction manager and Flyway wiring.
- Modify `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlProperties.java`: backend validation and explicit quality-evidence/backend consistency rules.
- Modify `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlService.java`: aggregate backend status and database-backed artifact state into shared readiness.
- Modify `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactDescriptor.java` and `PersistenceArtifactCatalog.java`: represent physical backend for quality evidence without treating a missing JSON file as corruption in PostgreSQL mode.
- Modify `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceSnapshotService.java`: reject PostgreSQL quality-evidence file snapshots with stable `PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED`.
- Create `apps/api/src/main/java/com/huawei/skillcenter/quality/JdbcQualityEvidenceStore.java`: PostgreSQL implementation of the existing repository port.
- Create `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceStateValidator.java`: shared package-level validation used by JSON and PostgreSQL stores.
- Create `apps/api/src/main/resources/db/migration/V1__create_quality_evidence_state.sql`: Flyway schema for the aggregate row.
- Create `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceBackendConfigurationTest.java`: backend selection, redaction and readiness tests.
- Create `apps/api/src/test/java/com/huawei/skillcenter/quality/JdbcQualityEvidenceStoreTest.java`: repository contract and transaction tests.
- Create `apps/api/src/test/java/com/huawei/skillcenter/quality/PostgresQualityEvidenceIntegrationTest.java`: real PostgreSQL/Testcontainers contract, with explicit capability skip when Docker is unavailable.
- Modify `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceStartupGateTest.java`: PostgreSQL status and fail-closed cases.
- Modify `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceSnapshotServiceTest.java`: PostgreSQL snapshot unsupported boundary.
- Modify `apps/api/pom.xml`: conditional PostgreSQL/Flyway/JDBC/Testcontainers dependencies without causing JSON mode to require a DataSource.
- Modify `apps/api/src/main/resources/application.yml`: documented safe defaults and non-secret PostgreSQL keys.
- Modify `docs/project/A1-persistence-control-plane-status.md`, `docs/project/M11-external-integration-runbook.md`, and `docs/project/remaining-coding-tasks-status.md`: A2 status, migration runbook, and explicit production boundary.

---

### Task 1: Freeze backend selection and redacted status contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceBackend.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceBackendStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/JsonPersistenceBackend.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceProperties.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlProperties.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceStartupGateTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceBackendConfigurationTest.java`

**Interfaces:**
- `PersistenceBackend.backendId(): String`
- `PersistenceBackend.status(): PersistenceBackendStatus`
- `PersistenceBackendStatus(String backendId, String state, String reasonCode, String schemaVersion, Long revision)` with no connection details.
- `PersistenceBackendStatus.ready(String backendId, String schemaVersion, Long revision)` and `PersistenceBackendStatus.failClosed(String backendId, String reasonCode, String schemaVersion, Long revision)` are the only production factories for stable states.
- `PersistenceControlProperties.normalizedBackend(): String` and `normalizedQualityEvidenceBackend(): String` return trimmed lowercase values for validation and conditional wiring.
- `PostgresPersistenceProperties` exposes URL, username, password and timeout only to configuration code; no `toString()` or status projection may include password.

- [x] **Step 1: Write the failing contract tests.**

```java
@Test
void jsonIsTheDefaultAndDoesNotRequireDatabaseConfiguration() {
    PersistenceControlProperties properties = new PersistenceControlProperties();

    assertThat(properties.normalizedBackend()).isEqualTo("json");
    assertThat(properties.normalizedQualityEvidenceBackend()).isEqualTo("json");
}

@Test
void unknownBackendIsRejectedWithAStableValidationFailure() {
    PersistenceControlProperties properties = new PersistenceControlProperties();
    properties.setBackend("oracle");

    assertThatThrownBy(() -> properties.validate(tempDir))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("backend must be json or postgresql");
}

@Test
void postgresStatusNeverContainsPasswordOrJdbcCredentials() {
    PersistenceBackendStatus status = PersistenceBackendStatus.failClosed(
            "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", "12", 4L);

    assertThat(status.toString()).doesNotContain("password", "jdbc:", "secret");
    assertThat(status.state()).isEqualTo("FAIL_CLOSED");
}
```

- [x] **Step 2: Run the focused test and verify it fails for the missing contracts.**

Run:

```powershell
mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=PersistenceBackendConfigurationTest" test
```

Expected: compilation or assertion failure because backend normalization, status type, and PostgreSQL mode validation do not exist.

- [x] **Step 3: Implement the minimal backend contracts and validation.**

Add normalized getters without exposing mutable raw configuration. Accept only `json` and `postgresql`; require `quality-evidence-backend=postgresql` to be paired with global `persistence.backend=postgresql`; retain `json` defaults. Make the status record’s `toString()` redacted by design and keep revision nullable for JSON.

- [x] **Step 4: Update the existing backend rejection regression and run focused tests.**

Change `PersistenceStartupGateTest.invalidBackendAndStoragePathAreRejectedBeforeStartup` to use `oracle` as the unsupported backend value; `postgresql` is now an accepted selection and must not be used as an invalid-backend fixture. Run the new test plus `PersistenceArtifactCatalogTest`, `PersistenceIntegrityServiceTest`, `PersistenceMigrationRegistryTest`, and `PersistenceStartupGateTest`. Expected: all pass; no JSON behavior changes.

- [x] **Step 5: Commit.**

Do not commit in the shared workspace; record the completed task in the SDD ledger instead.

### Task 2: Add conditional PostgreSQL DataSource and Flyway schema lifecycle

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceConfiguration.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PostgresPersistenceBackend.java`
- Create: `apps/api/src/main/resources/db/migration/V1__create_quality_evidence_state.sql`
- Modify: `apps/api/pom.xml`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/SkillCenterApiApplication.java` (disable conflicting Boot auto-configuration for JSON default)
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlProperties.java` (share backend normalization with conditional wiring)
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceBackendConfigurationTest.java`

**Interfaces:**
- `PostgresPersistenceConfiguration` creates `DataSource`, `JdbcTemplate`, `DataSourceTransactionManager`, and Flyway only under explicit `skill-center.persistence.backend=postgresql`.
- `PostgresPersistenceBackend.status()` performs a bounded read-only validation of DataSource/Flyway state and returns stable codes.
- JSON mode must not instantiate a DataSource and must keep Spring context tests working without `spring.datasource.url`.

- [x] **Step 1: Add a failing context/SQL contract test.**

```java
@Test
void postgresSchemaCreatesOneQualityEvidenceAggregateRowTable() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V1__create_quality_evidence_state.sql"));

    assertThat(sql).contains("CREATE TABLE skill_quality_evidence_state");
    assertThat(sql).contains("aggregate_key").contains("jsonb").contains("revision");
}

@Test
void jsonContextDoesNotCreatePostgresDataSource() {
    new ApplicationContextRunner()
            .withUserConfiguration(PersistenceConfiguration.class)
            .withPropertyValues("skill-center.persistence.backend=json")
            .run(context -> assertThat(context.getBeansOfType(DataSource.class)).isEmpty());
}
```

- [x] **Step 2: Run the test and confirm the schema/configuration is absent.**

Run the focused class. Expected: missing resource/configuration failure, not a skipped assertion.

- [x] **Step 3: Implement dependencies, migration and conditional wiring.**

Use `spring-jdbc`, HikariCP, Flyway core plus the PostgreSQL Flyway database module, PostgreSQL runtime driver, and Testcontainers PostgreSQL test dependencies. Avoid `spring-boot-starter-jdbc` auto-configuration if it would force a DataSource in JSON mode; create the DataSource only in the explicit conditional configuration. Configure connection and migration timeouts, never log password or JDBC credentials, and map migration failures to the stable status codes.

The migration must create:

```sql
CREATE TABLE skill_quality_evidence_state (
    aggregate_key text PRIMARY KEY,
    document_schema_version integer NOT NULL,
    revision bigint NOT NULL,
    payload jsonb NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT skill_quality_evidence_state_revision_non_negative CHECK (revision >= 0),
    CONSTRAINT skill_quality_evidence_state_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT skill_quality_evidence_state_singleton_key CHECK (aggregate_key = 'quality-evidence')
);
```

- [x] **Step 4: Run JSON and migration-focused tests.**

Run JSON Spring smoke, backend configuration tests, and migration resource checks. If Docker is available, start the Testcontainers contract in the next task; if not, report only the explicit capability skip.

- [x] **Step 5: Record the task evidence.**

Update `.superpowers/sdd/2026-08-25-postgresql-quality-evidence-adapter/progress.md` or the existing persistence ledger with dependency, migration, and JSON compatibility evidence; do not alter unrelated dirty files.

### Task 3: Implement the PostgreSQL Quality Evidence repository

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/JdbcQualityEvidenceStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceStateValidator.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceStore.java`
- Modify: `apps/api/pom.xml` (Testcontainers PostgreSQL test support)
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/JdbcQualityEvidenceStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/PostgresQualityEvidenceIntegrationTest.java`

**Interfaces:**
- `JdbcQualityEvidenceStore implements QualityEvidenceRepository`.
- Constructor consumes `JdbcTemplate`, `ObjectMapper`, and `PlatformTransactionManager`.
- `load/save/update/clear` preserve `QualityEvidenceRepository` semantics and throw the existing stable persistence exception family for invalid/corrupt state.
- `QualityEvidenceStateValidator.validate(QualityEvidenceState)` is package-visible and is called by both `QualityEvidenceStore` and `JdbcQualityEvidenceStore`; it throws `IllegalArgumentException` for all existing suite/run/snapshot/case/matrix invariant violations.

- [x] **Step 1: Write failing repository contract tests.**

Cover these exact behaviors:

```java
@Test
void loadReturnsEmptyStateWhenAggregateRowDoesNotExist() {
    JdbcQualityEvidenceStore store = store();

    assertThat(store.load()).isEqualTo(new QualityEvidenceState(
            List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of()));
}

@Test
void saveThenLoadRoundTripsQualityEvidenceAndIncrementsRevision() {
    JdbcQualityEvidenceStore store = store();
    QualityEvidenceState state = emptyStateWithRule("quality-v1");

    store.save(state);
    assertThat(store.load()).isEqualTo(state);
    assertThat(jdbcTemplate.queryForObject(
            "select revision from skill_quality_evidence_state where aggregate_key = ?",
            Long.class, "quality-evidence")).isEqualTo(1L);
}

@Test
void saveRejectsInvalidForeignEvidenceBeforeDatabaseWrite() {
    JdbcQualityEvidenceStore store = store();
    QualityEvidenceState invalid = stateWithSnapshotReferencingUnknownRun();

    assertThatThrownBy(() -> store.save(invalid))
            .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from skill_quality_evidence_state",
            Long.class)).isZero();
}

@Test
void updateLocksTheAggregateAndDoesNotSilentlyLoseAConcurrentRevision() {
    JdbcQualityEvidenceStore store = store();
    store.save(emptyStateWithRule("quality-v1"));

    store.update(state -> state);

    assertThat(jdbcTemplate.queryForObject(
            "select revision from skill_quality_evidence_state where aggregate_key = ?",
            Long.class, "quality-evidence")).isEqualTo(2L);
}

@Test
void failedTransactionLeavesThePreviousPayloadAndRevisionIntact() {
    JdbcQualityEvidenceStore store = store();
    QualityEvidenceState original = emptyStateWithRule("quality-v1");
    store.save(original);

    assertThatThrownBy(() -> store.update(state -> stateWithSnapshotReferencingUnknownRun()))
            .isInstanceOf(QualityEvidenceStore.QualityEvidencePersistenceException.class);
    assertThat(store.load()).isEqualTo(original);
    assertThat(jdbcTemplate.queryForObject(
            "select revision from skill_quality_evidence_state where aggregate_key = ?",
            Long.class, "quality-evidence")).isEqualTo(1L);
}

@Test
void clearIsIdempotentAndLeavesAValidEmptyAggregate() {
    JdbcQualityEvidenceStore store = store();

    store.clear();
    store.clear();

    assertThat(store.load().runs()).isEmpty();
    assertThat(store.load().snapshots()).isEmpty();
}
```

The test fixture provides `store()`, `emptyStateWithRule(String)`, and
`stateWithSnapshotReferencingUnknownRun()` as real builders in the test class;
the first uses the test container's `JdbcTemplate`, `ObjectMapper`, and transaction manager, while the latter intentionally violates the existing repository foreign-reference invariant.

Use real SQL behavior through a PostgreSQL Testcontainer when available. For unit-level error mapping, use a narrowly scoped `JdbcTemplate`/transaction test double only where a live database cannot deterministically inject a failure.

- [x] **Step 2: Run the tests and verify they fail for the absent adapter.**

Run the focused repository classes. Expected: compilation failure because `JdbcQualityEvidenceStore` is absent; Testcontainers capability skip is allowed only after the test class compiles and reports the reason.

- [x] **Step 3: Implement the minimal transactional adapter.**

Serialize with the configured `ObjectMapper`, call the same state validation used by the JSON store (extract it to a package-private validator only if needed), and use `TransactionTemplate` or `@Transactional` around `SELECT ... FOR UPDATE` plus update. On first save use revision `1`; on update require the current row revision and increment it. Convert malformed JSON, invalid schema, SQL/transaction failures, and revision conflicts to stable persistence exceptions without including the cause text in API responses.

- [x] **Step 4: Run repository, quality, and compatibility tests.**

Run `JdbcQualityEvidenceStoreTest`, `PostgresQualityEvidenceIntegrationTest`, existing quality evidence/service tests, and the persistence focused suite. Expected: JSON and PostgreSQL implementations satisfy the same domain invariants; no Prompt/Trace/token/credential leakage.

- [x] **Step 5: Record adapter evidence.**

Record whether Testcontainers ran or was capability-skipped, the schema version, round-trip result, transaction-failure result, and focused test counts in the A2 SDD ledger.

### Task 4: Integrate backend status, catalog semantics and snapshot boundary

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactDescriptor.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactCatalog.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceSnapshotService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceConfiguration.java` or `JsonPersistenceBackend.java` (normalized JSON backend bean wiring, if needed)
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceStartupGateTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceSnapshotServiceTest.java`

**Interfaces:**
- `PersistenceControlService` consumes `PersistenceBackend` status and aggregates it with JSON artifact statuses.
- `PersistenceArtifactDescriptor` exposes physical backend for quality evidence while retaining artifact ID/schema/critical/includeInSnapshot.
- `PersistenceSnapshotService` rejects a PostgreSQL-backed quality evidence file copy with `PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED` before creating a partial snapshot.

- [x] **Step 1: Write failing integration tests.**

```java
@Test
void postgresBackendFailureBlocksSharedStartupStatus() {
    PersistenceControlService service = serviceWithBackend(
            PersistenceBackendStatus.failClosed(
                    "postgresql", "PERSISTENCE_CONTROL_PLANE_ERROR", null, null));

    assertThat(service.status().overall()).isEqualTo("FAIL_CLOSED");
    assertThat(service.status().controlPlaneReasonCodes())
            .contains("PERSISTENCE_CONTROL_PLANE_ERROR");
}

@Test
void postgresQualityEvidenceDoesNotReportMissingJsonFileAsThePrimaryArtifactFailure() {
    PersistenceControlService service = serviceWithBackend(
            PersistenceBackendStatus.ready("postgresql", "1", 1L));

    assertThat(service.status().artifacts()).anySatisfy(artifact -> {
        assertThat(artifact.artifactId()).isEqualTo("quality-evidence");
        assertThat(artifact.state()).isEqualTo("READY");
    });
}

@Test
void fileSnapshotCreationRejectsPostgresQualityEvidenceBeforePublication() {
    PersistenceSnapshotService service = snapshotServiceWithPostgresQualityEvidence();

    assertThatThrownBy(service::createSnapshot)
            .isInstanceOf(PersistenceControlException.class)
            .hasMessageContaining("PERSISTENCE_SNAPSHOT_BACKEND_UNSUPPORTED");
    assertThat(publishedSnapshotDirectories()).isEmpty();
}
```

The integration fixture provides `serviceWithBackend(PersistenceBackendStatus)`,
`snapshotServiceWithPostgresQualityEvidence()`, and
`publishedSnapshotDirectories()` using the same catalog and temp-root builders as the existing A1 persistence tests; these helpers are test code, not new production APIs.

Assert stable status/reason codes, no snapshot directory publication, no live JSON modification, and no database credentials in responses.

- [x] **Step 2: Run tests to confirm current A1 behavior fails the new contract.**

Expected: current catalog always reports the JSON path and snapshot service either copies it or reports a generic missing/corrupt result; this proves the integration change is necessary.

- [x] **Step 3: Implement backend-aware status and snapshot guard.**

Use the backend status as a critical control-plane input when PostgreSQL is selected. Keep non-critical JSON artifacts’ existing `OPTIONAL_MISSING` behavior. Before snapshot temp-directory creation, check whether any included artifact is database-backed and return the stable unsupported result; do not write metadata for a rejected snapshot.

- [x] **Step 4: Run all persistence and quality regression tests.**

Run all A1 persistence suites, quality evidence/service/controller suites, and Spring wiring smoke. Expected: JSON snapshot creation remains unchanged; PostgreSQL mode fails closed when unavailable and rejects unsupported file snapshots deterministically.

- [x] **Step 5: Record completion evidence.**

Update the A2 ledger and preserve A1 report history; do not rewrite prior A1 claims to imply PostgreSQL backup support.

### Task 5: Verification, runbook and migration handoff

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `docs/project/A1-persistence-control-plane-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Create: `scripts/verify-postgres-quality-evidence.ps1`
- Create: `.superpowers/sdd/2026-08-25-postgresql-quality-evidence-adapter/progress.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`

- [x] **Step 1: Write verification script tests/checks.**

The script must run JSON compatibility tests, backend configuration tests, PostgreSQL integration tests with capability reporting, quality evidence tests, Spring smoke, Web tests/build, and `git diff --check`; it must propagate failures and never reset, clean, delete user data, or silently skip a failed integration.

- [x] **Step 2: Add non-secret configuration examples and runbook.**

Document JSON default, PostgreSQL opt-in, required environment-provided datasource fields, Flyway startup gate, offline JSON import comparison, explicit database backup prerequisite, rollback to unchanged JSON configuration, Testcontainers capability skip semantics, and the fact that PostgreSQL file snapshots are unsupported in this phase.

- [x] **Step 3: Run final verification.**

Run:

```powershell
mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=PersistenceBackendConfigurationTest,PersistenceStartupGateTest,PersistenceSnapshotServiceTest,JdbcQualityEvidenceStoreTest,PostgresQualityEvidenceIntegrationTest" test
mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 test
Push-Location apps/web
npm.cmd test
npm.cmd run build
Pop-Location
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-postgres-quality-evidence.ps1
git diff --check
```

Aggregate Surefire reports and require zero failures/errors; report Docker capability skips separately from test failures.

- [x] **Step 4: Complete review and handoff.**

Run an independent review against the A2 spec and this plan. The review must explicitly check JSON compatibility, fail-closed backend selection, migration repeatability, transaction/revision semantics, snapshot unsupported boundary, redaction, and no false claims of production database/backup readiness.

- [x] **Step 5: Update lifecycle roadmap.**

Record A2 Phase 1 completion and list remaining A2 work: database-consistent snapshots/PITR, governance and release domain migration, Skill metadata relational projections, production database provisioning, SSO/JWT, object storage, and backup/recovery drills.
