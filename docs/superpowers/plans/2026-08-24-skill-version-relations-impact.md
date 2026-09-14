# Skill 版本关系与影响分析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为企业 Skill 建立按具体版本绑定的关系资产和可审计下游影响分析。

**Architecture:** 使用独立 JSON 原子 Store 保存 `SkillRelation`，不改变既有 `GovernanceSnapshot`；`SkillRelationService` 负责关系校验、环检测、生命周期和反向图遍历。影响节点读取 GovernanceStore 的版本/安装快照和 ReleaseRecordStore 的 PRODUCTION 晋级事实，只提供只读证据，不改变发布门禁。

**Tech Stack:** Java 21, Spring Boot, Jackson JSON store, JUnit 5, Mockito/AssertJ, MockMvc, React/Vite, Node test runner.

**Spec:** `docs/superpowers/specs/2026-08-24-skill-version-relations-impact-design.md`

## Global Constraints

- 关系两端必须绑定具体 `skillId + version`。
- 只允许 `DEPENDS_ON`、`COMPOSES`、`REPLACES`；同复合业务键只能有一个 ACTIVE 关系。
- 源目标不能相同，ACTIVE 关系图不得有环；两端不得是 `withdrawn` 版本。
- 默认影响分析最多 5 层/100 节点，服务端硬上限 10 层/500 节点；超限返回 `truncated=true`。
- 不保存 Prompt、输入输出、Trace 正文、工具参数、Token、凭据或 Provider 异常正文。
- 无关系历史版本继续兼容现有审核、发布、安装和下载行为。

---

### Task 1: Build the relation model and atomic store

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationType.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelation.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationConflictException.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/relationship/SkillRelationStoreTest.java`

**Interfaces:**
- `SkillRelation.create(String relationId, String sourceSkillId, String sourceVersion, String targetSkillId, String targetVersion, SkillRelationType type, String declaredBy, Instant declaredAt)` returns an ACTIVE record.
- `SkillRelation.retire(String actor, String reason, Instant at)` returns a RETIRED record with immutable relation context.
- `SkillRelationStore.find(String relationId)`, `findAll(String sourceSkillId, String sourceVersion, String targetSkillId, String targetVersion, SkillRelationStatus status)`, `create(SkillRelation relation)`, and `replace(SkillRelation relation)` use stable ordering and atomic persistence.

- [x] **Step 1: Write failing store/model tests** for normalization, duplicate relation identity, active business-key conflict, immutable context, retire fields, restart recovery, and atomic replacement.
- [x] **Step 2: Run the focused test and verify missing model/store failures.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillRelationStoreTest" test`

- [x] **Step 3: Implement bounded records and JSON atomic persistence.**

The store must load an absent file as empty, reject duplicate IDs/active business keys during recovery, write to a sibling `.tmp` file and replace the configured path, and never persist request bodies or actor credentials.

- [x] **Step 4: Rerun the focused store tests.**

Expected: all model, conflict, retirement and restart tests pass.

### Task 2: Add relation service, cycle detection and impact analysis

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationQuery.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationImpact.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationImpactNode.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationCycleException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationVersionNotFoundException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationLimitException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/relationship/SkillRelationServiceTest.java`

**Interfaces:**
- `SkillRelationService.create(SkillRelationRequest request, Actor actor, String requestId)` returns `SkillRelation`.
- `SkillRelationService.retire(String relationId, String reason, Actor actor, String requestId)` returns `SkillRelation`.
- `SkillRelationService.list(SkillRelationQuery query, Actor actor)` returns `List<SkillRelation>`.
- `SkillRelationService.impact(String skillId, String version, SkillRelationQuery limits, Actor actor)` returns `SkillRelationImpact`.

- [x] **Step 1: Write failing service tests** for role checks, version lookup, duplicate rejection, self-edge, direct cycle, transitive cycle, retirement idempotency, missing relation, stable traversal and production/installation enrichment.
- [x] **Step 2: Run focused service tests and verify the service API is absent.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillRelationServiceTest" test`

- [x] **Step 3: Implement validation and audit-safe relation creation/retirement.**

Use `GovernanceStore.snapshot().versions()` for version existence/status and `RoleGuard` for `maintainer/reviewer/admin` access. Add audit metadata containing only relation ID, source/target identifiers, type, status and stable reason codes.

- [x] **Step 4: Implement reverse-edge impact traversal.**

For a root target `(skillId, version)`, build ACTIVE reverse adjacency from relation target to source; visit each node once, retain the shortest depth, stop at configured limits, sort by depth then identifiers, and calculate `productionPromoted` only from a matching PRODUCTION `ReleaseRecord` SHA-256 plus active installation counts from the Governance snapshot.

- [x] **Step 5: Rerun service and release regression tests.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillRelationServiceTest,ReleaseAdmissionServiceTest,VersionLifecycleServiceTest" test`

### Task 3: Expose secure relationship and impact APIs

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/relationship/SkillRelationController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/relationship/SkillRelationControllerTest.java`

**Interfaces:**
- `POST /api/v1/admin/skill-relations` creates an active relation.
- `GET /api/v1/admin/skill-relations` lists relations with optional source/target/status filters.
- `POST /api/v1/admin/skill-relations/{relationId}/retire` retires a relation.
- `GET /api/v1/admin/skill-relations/impact?skillId=&version=&maxDepth=&maxNodes=` returns the bounded impact report.

- [x] **Step 1: Write failing MockMvc tests** for create/list/retire/impact, reviewer read access, unauthorized access, stable errors, request ID propagation and sensitive-field absence.
- [x] **Step 2: Run controller tests to confirm missing mappings/error handlers.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=SkillRelationControllerTest" test`

- [x] **Step 3: Implement controller and stable exception mapping.**

Return `409` for relation conflicts/cycles, `404` for missing versions/relations, `400` for invalid limits, and never return exception causes or free-form internal messages.

- [x] **Step 4: Rerun relationship controller tests and API error contract tests.**

### Task 4: Add API client and version-history impact panel

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/tests/api-client.test.mjs`
- Test: `apps/web/tests/detail-view-interaction.test.mjs`

**Interfaces:**
- `getSkillRelationImpact(skillId, version, params = {})` calls the bounded impact endpoint.
- `listSkillRelations(params = {})`, `createSkillRelation(input)`, and `retireSkillRelation(relationId, reason)` call the relation APIs.
- Version history’s administrator lifecycle dialog renders impact count, truncation, relation type, affected version status, production admission state and active installation count; it remains read-only until an explicit relation-management action is chosen.

- [x] **Step 1: Write failing Node/React tests** for URL encoding, impact loading, empty state, truncation notice, and no automatic writes during dialog open.
- [x] **Step 2: Run the focused Web tests and verify the new client/UI behavior is absent.**

Run: `npm.cmd --prefix apps/web test -- --test-name-pattern="relation|impact"`

- [x] **Step 3: Add the client methods and bounded impact rendering.**

Render only server-provided identifiers, statuses, stable relation types and counts; do not render actor IDs, request bodies or sensitive operational fields.

- [x] **Step 4: Rerun all Web tests and production build.**

### Task 5: Document migration and complete verification

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/superpowers/specs/2026-08-24-skill-version-relations-impact-design.md`
- Modify: `docs/superpowers/plans/2026-08-24-skill-version-relations-impact.md`

- [x] **Step 1: Document relation migration and coverage boundaries.**

State that existing versions with no relations remain compatible, relation coverage is informational in this phase, and release admission is not automatically changed by a relation report.

- [x] **Step 2: Run full Web verification.**

Run: `npm.cmd --prefix apps/web test` and `npm.cmd --prefix apps/web run build`.

- [x] **Step 3: Run full API verification and aggregate Surefire results.**

Run: `mvn.cmd -q -f apps/api/pom.xml test`; require zero failures, zero errors and zero skipped tests.

- [x] **Step 4: Run diff, sensitive-field and contract checks.**

Run: `git diff --check`; scan relationship and impact code for Prompt, input/output, Trace body, token, credential and exception-cause persistence; confirm external Runtime/CD remains Mock or `CONTRACT_ONLY`.
