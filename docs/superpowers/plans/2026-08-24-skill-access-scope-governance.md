# Skill 维护资格与可见范围治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每个 Skill 建立可持久化的可见范围和维护资格，并让所有 Skill 资产入口复用同一套服务端授权判定。

**Architecture:** 在 `access` 模块中使用独立 `SkillScopeStore` 保存 Skill 范围元数据；`SkillAuthorizationService` 读取范围、治理团队和角色绑定，提供可见、管理、提交版本和可见 Skill 集合判定。市场、分发、生命周期、发布、关系和内容入口只调用授权服务，不复制 owner/team 条件；历史无范围记录按 PUBLIC 兼容回退。

**Tech Stack:** Java 21, Spring Boot, Jackson JSON atomic store, JUnit 5, Mockito/AssertJ, MockMvc, React/Vite, Node test runner.

**Spec:** `docs/superpowers/specs/2026-08-24-skill-access-scope-governance-design.md`

## Global Constraints

- 范围值只允许 `PUBLIC`、`TEAM`、`RESTRICTED`；`TEAM` 必须引用活动团队，`RESTRICTED` 必须有至少一个维护者。
- 没有 `SkillScope` 的历史 Skill 按 `PUBLIC` 有效范围处理；缺少显式维护者时仅以内存回退到最新非下架版本 `uploadedBy`。
- `SkillVisibilityContext` 只允许 `CATALOG`、`CONTENT`、`DISTRIBUTION`、`GOVERNANCE`，不把范围判定混入质量分数或发布门禁计算。
- 范围 Store 使用 JSON 原子替换；同一 Skill 只能有一条记录，更新使用严格 revision 乐观并发检查。
- 不保存 Prompt、输入输出、Trace 正文、工具参数、Token、凭据、团队成员详情或 Provider 异常正文。
- 前端权限隐藏不是安全边界；所有入口必须在服务端授权，隐藏资源使用稳定不可见/不存在语义。
- 保留既有构造器和旧请求兼容路径，避免破坏现有单元测试与无范围历史数据恢复。

---

### Task 1: Build scope model and atomic store

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillVisibility.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillVisibilityContext.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScope.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopeMutation.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopeStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopeConflictException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopePersistenceException.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/SkillScopeStoreTest.java`

**Interfaces:**
- `SkillScopeStore.find(String skillId)` returns `Optional<SkillScope>`.
- `SkillScopeStore.findAll()` returns stable `List<SkillScope>` sorted by `skillId`.
- `SkillScopeStore.create(SkillScope scope)` rejects duplicate Skill IDs.
- `SkillScopeStore.replace(SkillScope scope, int expectedRevision)` atomically increments revision and rejects stale revisions.
- `SkillScope` contains `skillId`, `SkillVisibility visibility`, `ownerTeamId`, sorted `maintainerUserIds`, revision and declared/updated audit timestamps.

- [x] **Step 1: Write failing store tests** for missing-file empty state, normalization, duplicate Skill ID, invalid visibility/team fields, stable maintainer ordering, restart recovery, stale revision conflict and atomic replacement.
- [x] **Step 2: Run the focused store test and confirm the missing access model/store failure.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillScopeStoreTest" test`

Expected: compilation fails because the access model and store do not exist.

- [x] **Step 3: Implement bounded records and JSON atomic persistence.**

Use `${skill-center.skill-scope-storage:./data/governance/skill-scopes.json}`. Normalize IDs and user lists, validate `PUBLIC/TEAM/RESTRICTED`, write a sibling `.tmp`, replace atomically with a non-atomic fallback, and never serialize request bodies or credentials.

- [x] **Step 4: Rerun the focused store test and verify restart/revision behavior.**

Run the same Maven command; expected result is all scope store tests passing with zero failures.

### Task 2: Implement centralized authorization and scope management API

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillAuthorizationService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopeNotFoundException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillNotVisibleException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillManageForbiddenException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopeInvalidException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopeController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/SkillAuthorizationServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/SkillScopeControllerTest.java`

**Interfaces:**
- `SkillAuthorizationService.effectiveScope(String skillId)` returns explicit scope or a PUBLIC fallback with latest non-withdrawn `uploadedBy` as an internal maintainer fallback.
- `requireVisible(String skillId, Actor actor, SkillVisibilityContext context)` returns void and throws `SkillNotVisibleException` for hidden resources.
- `requireManage(String skillId, Actor actor)` and `requireSubmitVersion(String skillId, Actor actor)` enforce admin bypass, active maintainer binding, team membership and explicit maintainer membership.
- `visibleSkillIds(Actor actor)` returns a stable set used by catalog filtering.
- `updateScope(String skillId, SkillScopeMutation mutation, Actor actor, String requestId)` is admin-only, creates revision 1 when absent, and appends only whitelisted audit metadata.
- `GET /api/v1/admin/skill-access/scopes?skillId=` returns a safe scope projection; `PUT /api/v1/admin/skill-access/scopes/{skillId}` accepts `{visibility, ownerTeamId, maintainerUserIds, revision}`.

- [x] **Step 1: Write failing service tests** for PUBLIC/TEAM/RESTRICTED visibility, active team membership, explicit maintainer membership, reviewer read-only access, admin bypass, historical PUBLIC fallback, missing/withdrawn versions, manage/submit denial and stable visible Skill ID ordering.
- [x] **Step 2: Run focused service tests and verify the centralized authorization API is absent.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillAuthorizationServiceTest" test`

Expected: compilation fails because the service and access exceptions do not exist.

- [x] **Step 3: Implement authorization from GovernanceSnapshot configuration.**

Treat active `RoleBinding.teamId` and active `TeamDefinition.memberUserIds` as membership evidence; reject inactive teams. Reviewers may read governance resources but cannot manage or submit. Ordinary developers may only read published/deprecated versions in visible scopes. Keep exception messages internal and map stable public error codes.

- [x] **Step 4: Add the scope controller and MockMvc contract tests.**

Return 200 for safe reads, 201 for first scope creation, 409 for stale revision, 400 for invalid scope, 403/404 for management and visibility errors, and omit internal actor/audit details from the response.

- [x] **Step 5: Rerun access service/controller and existing governance tests.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillAuthorizationServiceTest,SkillScopeControllerTest,GovernanceConfigurationServiceTest,RoleGuardTest" test`

### Task 3: Enforce scope at asset, lifecycle, release, relation and distribution boundaries

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillCatalogService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/VersionLifecycleService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactDownloadService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/SkillAuthorizationBoundaryTest.java`

**Interfaces:**
- Add actor-aware overloads `SkillCatalogService.list(SkillQuery, Actor)`, `detail(String, Actor)` and `content(String, Actor)` while retaining test-only compatibility overloads where existing callers require them.
- `ReviewService.submitValidatedPackage` calls `requireSubmitVersion` after package identity validation and before creating a pending version.
- Lifecycle and release mutation methods call `requireManage` before state transition or release creation; impact/read methods call `requireVisible` with `GOVERNANCE`.
- Relation creation checks source management and target visibility; list/impact omit hidden nodes rather than returning owner or scope metadata.
- Distribution checks `DISTRIBUTION` visibility before existing release admission and token issuance/consumption.

- [x] **Step 1: Write failing boundary tests** proving a non-member cannot list/detail/content/install/manage a TEAM or RESTRICTED Skill, an authorized team member can, admin bypasses, a maintainer cannot manage another Skill, historical unscoped Skill remains usable, and hidden relations do not leak downstream IDs.
- [x] **Step 2: Run boundary tests to verify current role-only paths fail the new assertions.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillAuthorizationBoundaryTest" test`

Expected: the new isolation assertions fail against existing role-only behavior.

- [x] **Step 3: Wire actor-aware authorization into catalog/content and mutation boundaries.**

Preserve admin/reviewer governance access, keep public historical behavior, ensure distribution rejects before issuing/consuming tokens, and preserve quality gate/release admission decisions after scope authorization succeeds.

- [x] **Step 4: Rerun boundary tests and focused lifecycle/release/relation/distribution regressions.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillAuthorizationBoundaryTest,VersionLifecycleServiceTest,ReleaseServiceTest,SkillRelationServiceTest,DistributionControllerTest,ArtifactControllerTest" test`

### Task 4: Add the administrator scope panel and API client

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/tests/api-client.test.mjs`
- Test: `apps/web/tests/detail-view-interaction.test.mjs`

**Interfaces:**
- `getSkillScope(skillId)`, `updateSkillScope(skillId, input)` call the scope endpoints.
- The administrator version/detail panel renders visibility, owner team, maintainer IDs, current revision, safe empty fallback and conflict error; non-admin sessions do not render governance fields.
- Opening the detail page and loading the scope uses GET only; PUT occurs only after explicit Save.

- [x] **Step 1: Write failing Node/React tests** for URL encoding, scope read, admin-only rendering, PUBLIC/TEAM/RESTRICTED selection, revision conflict message and no write during page load.
- [x] **Step 2: Run focused Web tests and verify the new scope client/panel behavior is absent.**

Run: `node --test tests/api-client.test.mjs tests/detail-view-interaction.test.mjs` from `apps/web`.

Expected: the new scope methods/panel assertions fail before implementation.

- [x] **Step 3: Implement the API client and explicit-save panel.**

Render only scope identifiers and revision; do not render audit actor details, team member details, tokens or operational evidence. Keep existing lifecycle relation impact panel read-only and independent.

- [x] **Step 4: Rerun all Web tests and production build.**

Run: `npm.cmd test` and `npm.cmd run build` from `apps/web`; require all tests passing and Vite build exit 0.

### Task 5: Document migration and complete verification

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`
- Modify: `docs/superpowers/specs/2026-08-24-skill-access-scope-governance-design.md`

- [x] **Step 1: Document compatibility migration and authorization boundaries.**

Record that unscoped historical Skills remain PUBLIC, explicit TEAM/RESTRICTED rules are fail-closed for new access, scope changes do not alter quality or release state, and SSO/organization synchronization remains an external deployment responsibility.

- [x] **Step 2: Run full Web verification.**

Run: `npm.cmd test` and `npm.cmd run build` from `apps/web`.

- [x] **Step 3: Run full API verification and aggregate Surefire results.**

Run: `mvn.cmd -q -f apps/api/pom.xml test`; aggregate every `apps/api/target/surefire-reports/TEST-*.xml` and require zero failures, errors and skipped tests.

- [x] **Step 4: Run full security, diff and contract checks.**

Run `git diff --check`; scan `apps/api/src/main/java/com/huawei/skillcenter/access` and modified boundary code for Prompt, input/output, Trace body, token, credential and exception-cause persistence; verify hidden-scope responses omit owner/team details and release admission remains unchanged.
