# Remaining Coding Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收口当前可在仓库内完成的剩余编码任务：两角色权限契约、市场/合集服务端查询分页，以及通知持久化与前端读取。

**Architecture:** 前端只暴露 `developer` 与 `admin` 两种角色；API 内部使用同一枚举并由权限守卫决定普通开发者与管理员能力。Skill 与合集列表统一由 API 完成查询、排序和分页，前端只渲染响应页和服务端 total。通知以治理快照为持久化边界，提供当前用户的列表、单条/全部已读接口，后续可由审核和生命周期事件写入。

**Tech Stack:** Spring Boot 3.4.5 / Java 21 / JSON snapshot store, React 19 / Vite, Node test runner.

**Spec:** `docs/superpowers/specs/2026-08-12-internal-skill-center-design.md` and the user-approved remaining-task audit.

## Global Constraints

- 网站不创建、编辑或执行 Skill；Skill 仍只能从本地 ZIP 发布。
- 前端用户角色只有普通开发者和平台管理员。
- 列表搜索、排序、分页必须使用 API 返回的 `total`，不能仅对截断数据做本地分页。
- 通知不得记录 Prompt、输出、Skill 正文、Token、设备标识或凭据。
- 所有写接口保持统一错误 envelope、requestId、权限校验和审计边界。

---

### Task 1: Two-role actor contract

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ActorResolver.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/RoleGuard.java` and affected role checks
- Modify: `apps/web/src/state.js`, `apps/web/src/api/client.js` or actor mapping
- Test: existing backend role/controller tests and `apps/web/tests/auth.test.mjs`, `apps/web/tests/state.test.mjs`

- [x] Write failing tests proving `developer` and `admin` are the canonical public roles and that developer/admin permissions remain distinct.
- [x] Run the focused tests and capture the legacy-role migration constraint.
- [x] Implement canonical role normalization and compatibility mapping at the API migration boundary so existing snapshots/tests remain readable.
- [x] Update role guards and actor headers so the UI sends canonical roles.
- [x] Run focused and full frontend/backend tests.

### Task 2: Server-side Skill market query

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillQuery.java`, repository/service/controller query sorting
- Modify: `apps/web/src/api/skillApi.js`, `apps/web/src/App.jsx`
- Test: API controller/service tests and `apps/web/tests/api-client.test.mjs`, `apps/web/tests/state.test.mjs`

- [x] Write failing tests for `sort`, server total, page 2, and a query result outside the first page.
- [x] Run them to observe the current first-page/first-50 limitation.
- [x] Add a validated server sort parameter for downloads, calls, favorites and updated time; return page metadata.
- [x] Make the Web market request the selected page and send query/filter/sort parameters; render `total` from the API response.
- [x] Run API and Web regression suites.

### Task 3: Server-side collection query

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/CollectionService.java` and public collection controller
- Modify: `apps/web/src/api/skillApi.js`, `apps/web/src/CollectionsView.jsx`
- Test: collection API tests and Web collection helper/client tests

- [x] Write failing tests for collection query, sort, page 2 and server total.
- [x] Run the focused tests and confirm current API/page-1 behavior fails.
- [x] Add validated query/sort parameters and page metadata to the public collection endpoint.
- [x] Make the collection page request on query/sort/page changes and stop filtering only the first page locally.
- [x] Run all collection and frontend regressions.

### Task 4: Persisted notification center

**Files:**
- Create/modify: notification record/service/controller under `apps/api/src/main/java/com/huawei/skillcenter/notification/`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceSnapshot.java` and `GovernanceStore.java`
- Modify: `apps/web/src/api/skillApi.js`, `apps/web/src/App.jsx`
- Test: notification service/controller tests and Web API/client tests

- [x] Write failing tests for unread listing, single read, mark-all-read, ownership filtering and restart persistence.
- [x] Run them to confirm the static frontend notifications do not satisfy the API contract.
- [x] Implement bounded notification records with safe fields, idempotent read mutations and snapshot persistence.
- [x] Replace static notification state with API loading/error/empty states while keeping the click interaction.
- [x] Run full frontend/backend tests and browser regression.

### Task 5: Verification and boundary report

- [x] Run `npm.cmd test`, `npm.cmd run build`, `mvn.cmd -B -q test`, and note that no Python runtime is available in this environment.
- [x] Smoke-test Web/API HTTP health, market page 2, collection page 2, and notification read persistence.
- [ ] Document external follow-ups separately: Huawei SSO/JWT, production database/object storage/queue/scanner, Redis/monitoring deployment, backup/RPO/RTO and M6 UAT/performance/security gates.
