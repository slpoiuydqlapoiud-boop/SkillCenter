# Department Windows MySQL Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 SkillCenter 的默认交付路径收敛为 ≤100 用户的 Windows 单机 + MySQL + 本地文件平台。

**Architecture:** 保留现有 Spring Boot/Vite 模块化单体和 Skill 生命周期领域能力；MySQL 作为唯一数据库，本地目录作为制品存储，进程内实现搜索、缓存和运营聚合。企业级外部依赖只保留适配器与历史文档，不进入启动门槛。

**Tech Stack:** Java 21, Spring Boot 3.4, Maven, MySQL 8.0+, Flyway, Node.js/Vite, PowerShell.

**Spec:** `docs/superpowers/specs/2026-09-09-department-windows-mysql-design.md`

## Global Constraints

- Windows 本地直接运行，默认启动脚本不得调用 Docker、docker compose 或 Kubernetes。
- 唯一外部数据库为 MySQL 8.0+，JDBC URL 使用 `jdbc:mysql://127.0.0.1:3306/skillcenter`。
- Skill 包只写入 `data/packages`，不得自动切换到 MinIO/S3。
- 默认单实例、≤100 用户；不把 Redis/OpenSearch/Kafka/SSO/JWKS 作为启动前置条件。
- 真实凭据不得提交；模板只使用占位值。

### Task 1: Reset the default local contract

**Files:**
- Modify: `deploy/local/.env.example`
- Modify: `deploy/local/application-integration.yml`
- Modify: `deploy/local/README.md`
- Modify: `scripts/start-local.ps1`
- Modify: `scripts/verify-environment.ps1`
- Test: `scripts/department-local-contract.Tests.ps1`

**Interfaces:** `start-local.ps1` checks Java/Maven/Node/MySQL and starts API/Web; no Docker dependency.

- [x] Write failing contract tests asserting MySQL-only environment and no Docker invocation.
- [x] Run the contract tests and verify they fail against the current PostgreSQL/Docker default.
- [x] Change env/config/start script to MySQL and local-file defaults.
- [x] Run the contract tests and the existing environment tests.

### Task 2: Add MySQL runtime and migration support

**Files:**
- Modify: `apps/api/pom.xml`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/MysqlPersistenceConfiguration.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/persistence/MysqlPersistenceBackend.java`
- Create: `apps/api/src/main/resources/db/migration-mysql/V*.sql`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/persistence/PersistenceBackendConfigurationTest.java`

**Interfaces:** `MysqlPersistenceBackend.status()` returns the existing redacted `PersistenceBackendStatus`; Flyway uses `db/migration-mysql`.

- [x] Write a failing test for MySQL selector and JDBC URL binding.
- [x] Run the focused test and verify the MySQL backend is absent.
- [x] Add the MySQL driver/Flyway module, datasource binding, and vendor migrations.
- [x] Add the department governance aggregate MySQL JSON repository with optimistic revision updates.
- [x] Add the department Quality Evidence MySQL JSON repository and shared document table migration.
- [x] Replace PostgreSQL JSON casts/locks/conflict syntax in the remaining selected department stores with MySQL-safe JSON-document transactions: Benchmark, Release, Execution Environment, Skill Scope, Skill Relation, Optimization Work Item/Experiment/Observation/Assessment, and Production Evidence.
- [x] Add a test-only MySQL Testcontainers integration covering Flyway V1/V2, JSON document write/read, and optimistic revision updates; Docker remains outside the department runtime.
- [x] Add a full department-selector Spring context Testcontainers integration covering MySQL migration ordering and all MySQL stores plus memory runtime summaries, JSON search, and local authentication.
- [x] Make the automatic optimization reconciliation gate accept both ready PostgreSQL and MySQL shared backends; add a MySQL positive regression so the department experiment → Benchmark → decision loop is not blocked by a PostgreSQL-only guard.
- [x] Run focused persistence/adapter contract tests and the full API regression.
- [ ] Run a real department-profile MySQL smoke test after local MySQL 8.0+ is listening on 127.0.0.1:3306; this is the only remaining runtime acceptance item in Task 2.

### Task 3: Make local filesystem the artifact default

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactStorageBackendConfiguration.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/LocalResumableUploadStore.java`
- Test: existing artifact/package storage tests plus a path-safety regression test.

- [x] Add a failing test for normalized root containment and atomic write (existing local storage regression suite).
- [x] Implement the local-only defaults and preserve package limits.
- [x] Run artifact and package upload tests as part of the API regression.

### Task 4: Reduce authentication to local department roles

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ActorAuthenticationProperties.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ActorResolver.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/RoleGuard.java`
- Modify: `apps/web/src/auth.js`, `apps/web/src/App.jsx`, `apps/web/tests/login-view.test.mjs`.

- [x] Add failing tests for local login, role propagation, and unauthenticated API rejection.
- [x] Implement configurable local accounts with PBKDF2 password hashes, short-lived in-memory bearer sessions, guest entry, and ADMIN/MEMBER/VIEWER role mapping.
- [x] Verify Web login client behavior and bearer propagation; real department API login remains blocked until MySQL is available.

### Task 5: Windows start and smoke acceptance

**Files:**
- Create: `scripts/start-department-local.ps1`
- Create: `scripts/verify-department-local.ps1`
- Modify: `docs/project/environment-dependencies.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Test: PowerShell contract tests and API/Web smoke scripts.

- [x] Add failing tests for prerequisites, MySQL readiness, API health, and Web HTTP 200.
- [x] Implement start/verify scripts with safe, read-only diagnostics and authenticated API readiness.
- [x] Add an explicit `bootstrap-department-mysql.ps1` helper for local MySQL database/user initialization; it uses `mysql.exe`, keeps passwords out of command-line arguments, and never writes `.env`.
- [x] Add `smoke-department-local.ps1` with a read-only default for authenticated Skill lifecycle reads; make evaluation/quality-evidence writes explicit via `-IncludeEvaluation`.
- [ ] Run a real department-profile MySQL smoke verification; blocked until local MySQL 8.0+ is listening on 127.0.0.1:3306.

### Task 6: Retire enterprise deployment from the default path

**Files:**
- Modify: `deploy/k8s/skillcenter/README.md`
- Modify: `deploy/prod/README.md`
- Modify: `docs/architecture/ADR-0001-platform-foundation.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`

- [x] Add explicit historical-reference labels and department-default links.
- [x] Verify no department start or environment script requires cloud/Kubernetes artifacts.

## Verification

Run the full API suite, Web suite, PowerShell contract suite, `scripts/verify-department-local.ps1`, and a real MySQL smoke test. Completion requires exit code 0 and explicit reporting of any unavailable optional provider as `CONTRACT_ONLY`.
