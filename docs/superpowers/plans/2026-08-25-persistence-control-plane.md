# Skill 生命周期持久化控制面实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为现有 JSON 持久化增加统一的资产目录、迁移登记、完整性检查、一致性快照和恢复预检能力，为后续 PostgreSQL/Flyway 适配冻结稳定边界。

**Architecture:** 新增独立 `persistence` 控制面，不改变现有业务 Store 的数据格式和 API。`PersistenceArtifactCatalog` 从现有配置构造文件/目录资产描述，`PersistenceIntegrityService` 负责哈希与结构摘要，`PersistenceSnapshotService` 负责临时目录原子发布和恢复预检，`PersistenceMigrationRegistry` 负责版本登记与幂等 journal；管理员 Controller 只返回状态/摘要，不返回业务正文。

**Tech Stack:** Java 21, Spring Boot 3.4.5, Jackson, SHA-256, NIO atomic move, JUnit 5, MockMvc, existing `ActorResolver` and `GlobalExceptionHandler`.

**Spec:** `docs/superpowers/specs/2026-08-25-persistence-control-plane-design.md`

## Global Constraints

- 仅支持 `skill-center.persistence.backend=json`；未知后端或非法持久化根目录必须 fail-closed。
- 不改变现有 JSON payload、Skill 生命周期、质量门禁、发布准入或范围授权语义。
- 快照/状态 API 只返回 artifact ID、状态、版本、大小、计数、SHA-256 和稳定原因码，不返回业务正文、Prompt、Trace、工具参数、凭据或 Provider 异常正文。
- 文件和目录路径必须解析为绝对规范路径，并验证位于配置的业务数据根或控制面根内；禁止路径穿越和符号链接逃逸。
- 所有控制面 JSON 写入使用同目录临时文件/目录和原子替换；Windows 不支持原子替换时使用明确的非原子回退并保持旧文件。
- 既有业务 Store 的启动校验和原子写入继续执行，控制面不得绕过领域校验。
- 管理接口仅管理员可用；GET 请求不能创建快照或写入 migration journal。
- 测试命令从 `D:\github\SkillCenter` 执行；API 全量回归使用 `-DforkCount=0`。

---

### Task 1: Define persistence artifacts, configuration and stable status contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactKind.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactState.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactDescriptor.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlProperties.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceArtifactCatalog.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceArtifactCatalogTest.java`

**Interfaces:**
- `PersistenceArtifactCatalog.artifacts()` returns a stable artifact-ID-sorted list.
- `PersistenceArtifactCatalog.find(String artifactId)` returns an `Optional<PersistenceArtifactDescriptor>`.
- `PersistenceArtifactDescriptor` contains `artifactId`, `kind`, `schemaVersion`, normalized absolute `storagePath`, `critical`, and `includeInSnapshot`.
- `PersistenceControlProperties` binds `backend`, `controlStorage`, `snapshotStorage`, `startupMode`, and `manifestRetention`.

- [x] **Step 1: Write failing catalog tests.**

Cover stable ordering, normalized paths, duplicate IDs, invalid schema version, invalid backend, snapshot root escaping the control root, optional missing package storage, and the invocation-events mapping to `governance-state` rather than a duplicate file.

- [x] **Step 2: Run the focused catalog test and confirm the control-plane types are absent.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=PersistenceArtifactCatalogTest" test`

Expected: compilation failure because the persistence package does not exist.

- [x] **Step 3: Implement properties, descriptors and catalog.**

Register the configured assets from the existing storage properties. Use `governance-state` for persisted invocation events, mark optional assets explicitly, reject duplicate registrations, and expose no raw path outside the service layer.

- [x] **Step 4: Run the focused catalog tests.**

Run the same Maven command; expect all catalog tests green with deterministic artifact order.

### Task 2: Implement integrity inspection and migration journal

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceIntegrityService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceMigration.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceMigrationJournal.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceMigrationRegistry.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlException.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceIntegrityServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceMigrationRegistryTest.java`

**Interfaces:**
- `PersistenceIntegrityService.inspect(PersistenceArtifactDescriptor)` returns `PersistenceArtifactStatus`.
- `PersistenceMigration` exposes `artifactId()`, `fromVersion()`, `toVersion()`, and `apply(Path)`.
- `PersistenceMigrationRegistry.ensureCurrent(List<PersistenceArtifactDescriptor>)` returns a stable list of statuses and persists only allow-listed journal metadata.
- Stable control errors include `PERSISTENCE_ARTIFACT_MISSING`, `PERSISTENCE_ARTIFACT_CORRUPTED`, `PERSISTENCE_MIGRATION_REQUIRED`, `PERSISTENCE_MIGRATION_UNSUPPORTED`, and `PERSISTENCE_MIGRATION_FAILED`.

- [x] **Step 1: Write failing integrity and migration tests.**

Cover JSON file SHA-256/size/root-count, directory manifest digest, optional missing, malformed JSON, unsupported version path, idempotent migration, failed migration preserving original bytes, and journal restart recovery.

- [x] **Step 2: Run focused tests to confirm the services are absent.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=PersistenceIntegrityServiceTest,PersistenceMigrationRegistryTest" test`

Expected: compilation failure before implementation.

- [x] **Step 3: Implement inspection and migration registry.**

Compute SHA-256 without returning file content; count only JSON root records/fields; use a sibling temporary path for migration; update journal atomically after successful replacement; never downgrade versions or silently skip unsupported paths.

- [x] **Step 4: Run focused integrity and migration tests.**

Require all tests green and assert the original artifact remains byte-identical after a failed migration.

### Task 3: Implement atomic snapshots and restore preflight

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceSnapshotManifest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceSnapshotArtifact.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceSnapshotService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceSnapshotStore.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceSnapshotServiceTest.java`

**Interfaces:**
- `PersistenceSnapshotService.createSnapshot()` returns a complete `PersistenceSnapshotManifest` and never exposes copied bytes.
- `PersistenceSnapshotService.listSnapshots()` returns stable metadata sorted newest first.
- `PersistenceSnapshotService.getSnapshot(String snapshotId)` returns `PERSISTENCE_SNAPSHOT_NOT_FOUND` for unknown IDs.
- `PersistenceSnapshotService.restorePreflight(String snapshotId)` returns `READY` or `BLOCKED` with stable reason codes and never mutates configured data paths.

- [x] **Step 1: Write failing snapshot tests.**

Cover multi-file and directory copy, manifest SHA-256, deterministic artifact order, temporary snapshot invisibility, incomplete snapshot rejection, tampered data rejection, path escape rejection, and no target mutation during preflight.

- [x] **Step 2: Run focused snapshot tests and confirm missing implementation.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=PersistenceSnapshotServiceTest" test`

Expected: compilation failure before implementation.

- [x] **Step 3: Implement atomic snapshot and preflight.**

Create under a sibling temporary directory, copy only registered `includeInSnapshot` assets, write manifest and digest, fsync where supported, then atomically rename the completed directory. Restore preflight must validate manifest digest, asset membership, relative paths, and every copied asset hash without replacing any live file.

- [x] **Step 4: Run focused snapshot tests and restart verification.**

Restart the snapshot service against the same snapshot root and require the same manifest list and digest values.

### Task 4: Add admin persistence status and snapshot APIs

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceControllerTest.java`

**Interfaces:**
- `GET /api/v1/admin/persistence/status` returns artifact status and overall `READY`/`DEGRADED`/`FAIL_CLOSED` state.
- `POST /api/v1/admin/persistence/snapshots` creates a complete snapshot and returns metadata only.
- `GET /api/v1/admin/persistence/snapshots` and `GET /api/v1/admin/persistence/snapshots/{snapshotId}` return metadata only.
- `POST /api/v1/admin/persistence/snapshots/{snapshotId}/restore-preflight` returns non-mutating readiness.

- [x] **Step 1: Write failing MockMvc tests.**

Cover admin success, viewer/reviewer denial, GET side-effect absence, snapshot creation, stable error mapping, no raw path/data in JSON, and restore preflight that does not modify an artifact.

- [x] **Step 2: Run focused controller tests to confirm endpoints are absent.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=PersistenceControllerTest" test`

Expected: compilation or 404 failure before implementation.

- [x] **Step 3: Implement control service, controller and error mapping.**

Resolve actor through `ActorResolver`, require `admin`, map internal persistence failures to stable codes, and return only allow-listed status/manifest fields.

- [x] **Step 4: Run focused controller tests.**

Require all MockMvc tests green, including absence of response fields named content, body, prompt, trace, credential, token, or cause.

### Task 5: Wire startup gate, documentation and verification

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/SkillCenterApiApplication.java` or startup configuration as required
- Create: `scripts/verify-persistence-control.ps1`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Create: `docs/project/A1-persistence-control-plane-status.md`

- [x] **Step 1: Write startup-gate and script tests.**

Cover missing optional assets, missing critical assets, malformed journal, unsupported backend, and clean startup with all current JSON stores.

- [x] **Step 2: Implement fail-closed startup readiness without changing business behavior.**

Run catalog inspection and migration journal recovery at startup; expose degraded status through the control API. Do not make ordinary Skill catalog or quality requests write migration state.

- [x] **Step 3: Add the verification script and operator documentation.**

The script runs focused persistence tests, API full tests with `-DforkCount=0`, Web tests/build, and snapshot tamper/preflight checks. Documentation describes setup, status codes, snapshot retention, offline restore workflow, and A2 PostgreSQL handoff.

- [x] **Step 4: Run final verification and aggregate evidence.**

Run:

```powershell
mvn.cmd -q -f apps/api/pom.xml "-DforkCount=0" test
Push-Location apps/web
npm.cmd test
npm.cmd run build
Pop-Location
git diff --check
```

Aggregate all `apps/api/target/surefire-reports/TEST-*.xml`; require zero failures, errors, and skipped tests. Scan persistence code and snapshot response DTOs for business正文, Prompt, Trace, tool arguments, token, credential, or exception-cause leakage.
