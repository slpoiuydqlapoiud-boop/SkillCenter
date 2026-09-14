# Organization Directory Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将企业组织目录安全同步为可过期、可审计的本地 TEAM 授权事实快照。

**Architecture:** `OrganizationDirectoryClient` 负责受控 HTTP 拉取，`OrganizationDirectorySnapshot` 负责 bounded contract，`OrganizationDirectoryStore` 负责原子持久化和状态，`OrganizationDirectorySyncService` 负责幂等/回退/审计。TEAM 授权在 HTTP 模式消费 ACTIVE 且未过期的目录快照，并与 JWT team claim 共同形成 fail-closed 判定；local 模式保持旧行为。

**Tech Stack:** Java 21、Spring Boot 3、Jackson、JDK HttpClient、JUnit 5、Spring MockMvc。

**Spec:** `docs/superpowers/specs/2026-08-25-organization-directory-snapshot-design.md`

## Global Constraints

- 请求路径不得访问外部目录；只有管理员显式同步触发网络请求。
- HTTP 模式失败、过期、回退 revision、非法响应均不得回退本地成员表。
- 不保存 Token、原始响应、用户属性或外部异常正文。
- 保留 local Actor、JWT Actor、现有 Skill scope、RoleBinding 和发布语义兼容。
- 所有生产代码先由失败测试证明缺口，再实现最小通过版本。

### Task 1: Snapshot and properties contract

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectorySnapshot.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectoryProperties.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/OrganizationDirectorySnapshotTest.java`

- [x] Write tests for bounded team/member IDs, deterministic immutable ordering, duplicate rejection, count limits, mode/URL/credential validation, timeout and age bounds.
- [x] Run the focused test and confirm RED because the new types and validators do not exist.
- [x] Implement the record and Spring properties with exact limits from the spec.
- [x] Run the focused test and confirm GREEN.

### Task 2: Store and client contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectoryState.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectoryStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectoryClient.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectoryUnavailableException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/OrganizationDirectoryStoreTest.java`

- [x] Write tests for empty state, atomic restart recovery, accepted snapshot hash, duplicate identical revision, changed same revision, older revision, failure state and stale state.
- [x] Run the focused store test and confirm RED.
- [x] Implement immutable state and atomic temp-file replacement without deleting the last diagnostic state.
- [x] Run the focused store test and confirm GREEN.

### Task 3: HTTP adapter and synchronization

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/HttpOrganizationDirectoryClient.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectorySyncService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectoryStatus.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/HttpOrganizationDirectoryClientTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/OrganizationDirectorySyncServiceTest.java`

- [x] Write HTTP server tests for HTTPS/loopback URL policy, Bearer header, timeout/size limits, strict schema, non-2xx and redacted failure.
- [x] Run the focused HTTP test and confirm RED.
- [x] Implement JDK HttpClient fetch, strict Jackson allowlist parsing, bounded response read and stable errors.
- [x] Write service tests for local mode skip, successful sync, idempotency, revision conflict, failed sync preservation and audit metadata.
- [x] Run focused sync tests and confirm RED.
- [x] Implement sync orchestration and state transitions; never replace a valid snapshot on failed fetch.
- [x] Run the focused adapter/sync tests and confirm GREEN.

### Task 4: Authorization and admin control plane

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillAuthorizationService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/OrganizationDirectoryController.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/SkillAuthorizationOrganizationDirectoryTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/OrganizationDirectoryControllerTest.java`

- [x] Write authorization tests for HTTP mode directory membership, JWT claim plus directory membership, stale/failed denial, local compatibility and maintainer binding preservation.
- [x] Run the focused authorization test and confirm RED.
- [x] Add an optional directory membership authority to SkillAuthorizationService while preserving existing constructors used by local tests.
- [x] Run focused authorization tests and confirm GREEN.
- [x] Add admin-only GET status and POST sync endpoints with safe response projection and stable errors.
- [x] Run controller tests and confirm GREEN.

### Task 5: Configuration, readiness, docs and verification

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/PlatformReadinessService.java`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/project/M11-regression-review-status.md`

- [x] Add safe default local configuration and environment-backed HTTP settings.
- [x] Add organization directory readiness component without making local development unavailable.
- [x] Document sync, expiry, revision and external acceptance boundary.
- [x] Run full API, Web tests/build, lifecycle verifier and `git diff --check`.
