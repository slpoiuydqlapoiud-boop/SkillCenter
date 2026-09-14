# Security Evidence Lifecycle Projection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Skill 版本安全扫描元数据纳入生命周期投影和源哈希，并以 V3 关系型 schema 持久化安全 finding 摘要。

**Architecture:** GovernanceStore 是安全证据事实源；`SkillLifecycleProjectionSource` 将其转换为受约束的生命周期版本行。JSON read-through 和 PostgreSQL import 共用同一 snapshot/hash 契约，PostgreSQL 用版本表列加 finding 子表保存 metadata-only 证据。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, PostgreSQL/Flyway, JdbcTemplate, Node/Vite verifier。

**Spec:** `docs/superpowers/specs/2026-08-25-security-evidence-lifecycle-projection-design.md`

## Global Constraints

- 安全证据只允许 `PASSED`、`BLOCKED`、`NOT_SCANNED`；旧记录默认 `NOT_SCANNED / legacy-compatible`。
- 投影只保存 scanner provenance 和 `code/path/severity` 摘要；禁止 finding reason、命中内容、Prompt、Trace、凭据。
- JSON 仍是默认事实源；不得运行时双写、自动切换或用 Mock 冒充外部安全扫描。
- 任何源约束失败必须 fail-closed；PostgreSQL 导入必须事务性重建版本和 finding 子表。

### Task 1: Freeze lifecycle security evidence contracts with tests

**Files:**
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionHasherTest.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionSourceTest.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/JsonSkillLifecycleProjectionStoreTest.java`

**Interfaces:**
- Consumes: existing `SkillVersion.securityEvidence()` and lifecycle projection constructors.
- Produces: failing tests for evidence conversion, legacy compatibility, deterministic findings and hash sensitivity.

- [x] **Step 1: Write failing tests** for a version with passed evidence, a blocked finding, a legacy version, and two inputs whose finding order differs.
- [x] **Step 2: Run focused lifecycle tests** with `mvn -f apps/api/pom.xml -Dtest=SkillLifecycleProjectionHasherTest,SkillLifecycleProjectionSourceTest,JsonSkillLifecycleProjectionStoreTest test`; confirmed failures were missing lifecycle evidence fields, not test setup errors.

### Task 2: Add safe lifecycle row and source conversion

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleSecurityFindingRow.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleVersionRow.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionSource.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/SkillLifecycleProjectionHasher.java`

**Interfaces:**
- Consumes: `SecurityScanEvidence` from `SkillVersion`.
- Produces: immutable version rows with normalized status, scanner provenance and finding summaries; canonical hash includes all safe evidence.

- [x] **Step 1: Add bounded finding row validation** for code/path/severity and immutable list handling.
- [x] **Step 2: Add evidence fields to version row** while retaining an overload for existing tests and legacy callers.
- [x] **Step 3: Convert governance evidence in the source** and default null/legacy evidence safely.
- [x] **Step 4: Extend canonical hash** with deterministic evidence fields and finding ordering.
- [x] **Step 5: Run Task 1 focused tests** and confirm GREEN.

### Task 3: Persist evidence in PostgreSQL V3

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V3__add_skill_lifecycle_security_evidence.sql`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/lifecycle/PostgresSkillLifecycleProjectionStore.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/lifecycle/PostgresSkillLifecycleProjectionStoreTest.java`

**Interfaces:**
- Consumes: `SkillLifecycleProjectionSnapshot` evidence fields.
- Produces: schema version 3, version-row provenance columns, finding child table, atomic import and round-trip mapping.

- [x] **Step 1: Add V3 migration** with compatibility defaults, foreign key, bounded checks and metadata schema update.
- [x] **Step 2: Extend PostgreSQL version mapping and inserts** with scanner provenance and finding child rows.
- [x] **Step 3: Delete child findings before parent versions** and keep count/transaction failure behavior unchanged.
- [x] **Step 4: Add round-trip and replacement tests** proving old finding rows cannot survive a replacement.
- [x] **Step 5: Run focused PostgreSQL tests**; only the named Docker capability skip remained when Docker was unavailable.

### Task 4: Full verification and documentation

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/progress.md`

- [x] **Step 1: Run focused API and Web regressions.** Focused API lifecycle/authorization and Web 141/141 regressions passed.
- [x] **Step 2: Run `scripts/verify-skill-lifecycle-projection.ps1`.** All six verifier checks passed.
- [x] **Step 3: Update the open-task status only if verifier confirms the projection/hash item is complete; keep external scanning and production delivery open.** Projection/hash is checked off; the two production boundaries remain open.
- [x] **Step 4: Run `git diff --check` and record exact counts and named capability skips.** Whitespace check passed; only the two named Docker capability-skip suites remain.
