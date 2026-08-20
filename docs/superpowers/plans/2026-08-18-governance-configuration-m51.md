# M5.1 Governance Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. The workspace has no Git repository, so do not issue commit, reset, or branch commands.

**Goal:** Deliver a real, audited governance configuration workbench for teams, role bindings, taxonomy, collections, and platform policy while preserving all existing Skill and distribution contracts.

**Architecture:** Extend the existing `GovernanceSnapshot`/`GovernanceStore` with a versioned configuration section and expose mutations only through `GovernanceConfigurationService`. Admin and public-read controllers map validated DTOs to the existing `ApiResponse` envelope; catalog and collection reads consume the active configuration without editing Skill package content. The React app adds a governance settings workbench and real collections page through `skillApi.js`.

**Tech Stack:** Java 21, Spring Boot 3, Jackson, JUnit/MockMvc, local JSON persistence; React, Vite, Node built-in tests, existing CSS and API client.

## Global Constraints

- Skill content remains local ZIP upload only; no online authoring, editing, repackaging, or draft state.
- Local identity remains `X-User-Id`/`X-User-Role`; only `admin` may write governance configuration, `reviewer` may read governance dictionaries, and ordinary users may read only effective public/team-scoped data.
- DELETE operations are idempotent deactivation; M5.1 never physically deletes configuration IDs or historical references.
- Existing versions, reviews, installations, authorizations, favorites, audits, market APIs, distribution APIs, and M4.3 error codes remain backward compatible.
- Missing governance fields in old JSON snapshots load as empty collections and the documented default policy; every mutation preserves all existing snapshot collections.
- Team visibility and role bindings are local configuration adapters, not claims of production SSO or organization-directory integration.
- Every write mutation and audit record is persisted atomically; metadata never stores Skill正文、prompt、output、token、device ID or other sensitive payloads.
- Verification commands are `mvn -B -q -f apps/api/pom.xml test`, `npm.cmd test --prefix apps/web`, and `npm.cmd run build --prefix apps/web`.

---

### Task 1: Governance configuration models and snapshot persistence

**Files:**

- Create `apps/api/src/main/java/com/huawei/skillcenter/governance/TeamDefinition.java`
- Create `RoleBinding.java`, `CategoryDefinition.java`, `TagDefinition.java`, `CollectionDefinition.java`, `PlatformPolicy.java`, `GovernanceConfiguration.java`
- Modify `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceSnapshot.java`
- Modify `GovernanceStore.java`
- Create `apps/api/src/test/java/com/huawei/skillcenter/governance/GovernanceConfigurationStoreTest.java`

**Interfaces:**

- `GovernanceConfiguration` owns `List<TeamDefinition> teams`, `List<RoleBinding> roleBindings`, `List<CategoryDefinition> categories`, `List<TagDefinition> tags`, `List<CollectionDefinition> collections`, and `PlatformPolicy platformPolicy`.
- `GovernanceSnapshot.configuration()` returns the normalized `GovernanceConfiguration`.
- `GovernanceStore.updateGovernanceConfiguration(GovernanceConfiguration configuration, AuditEvent audit)` atomically replaces only configuration fields, appends the optional audit, persists JSON, and preserves versions/reviews/installations/audits/authorizations/favorites.
- `GovernanceStore.snapshot()` remains the only read source for configuration and all legacy callers continue compiling.

- [ ] **Step 1: Write failing compatibility tests**

  Add tests that load a pre-M5.1 JSON snapshot without configuration fields and assert empty teams/taxonomy/collections plus policy defaults: `pageSizeOptions=[12,24,48]`, `maxPageSize=48`, `minimumClientVersion="1.0.0"`, `defaultCollectionVisibility="public"`, `policyVersion=1`.

- [ ] **Step 2: Run the focused tests and verify failure**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceConfigurationStoreTest test` and expect compilation failures for the new records/accessors.

- [ ] **Step 3: Implement immutable records and compatibility constructors**

  Validate stable IDs, nonblank names, nonnegative sort order, allowed enum values, copied lists, and policy defaults in compact constructors. Add the new fields to `GovernanceSnapshot` with null-safe normalization and retain all existing compatibility constructors.

- [ ] **Step 4: Implement atomic store replacement**

  Add `updateGovernanceConfiguration`, normalize null lists/policy, append the supplied audit only when non-null, and construct the next snapshot with every pre-existing collection passed through unchanged.

- [ ] **Step 5: Verify persistence and preservation**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceConfigurationStoreTest test`; it must pass for restart recovery, defaults, audit persistence, and preservation of versions/installations/authorizations/favorites.

### Task 2: Configuration validation and mutation service

**Files:**

- Create `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceConfigurationService.java`
- Create `TeamMutation.java`, `RoleBindingMutation.java`, `TaxonomyMutation.java`, `CollectionMutation.java`, `PolicyMutation.java`, `GovernanceConfigurationView.java`
- Create `ConfigConflictException.java`, `CollectionNotFoundException.java`
- Modify `GlobalExceptionHandler.java`
- Create `apps/api/src/test/java/com/huawei/skillcenter/governance/GovernanceConfigurationServiceTest.java`

**Interfaces:**

```java
GovernanceConfigurationView read(Actor actor);
TeamDefinition upsertTeam(TeamMutation request, Actor actor, String requestId);
void deactivateTeam(String teamId, Actor actor, String requestId);
RoleBinding upsertRoleBinding(String userId, RoleBindingMutation request, Actor actor, String requestId);
void deactivateRoleBinding(String userId, Actor actor, String requestId);
CategoryDefinition upsertCategory(TaxonomyMutation request, Actor actor, String requestId);
void deactivateCategory(String code, Actor actor, String requestId);
TagDefinition upsertTag(TaxonomyMutation request, Actor actor, String requestId);
void deactivateTag(String code, Actor actor, String requestId);
CollectionDefinition upsertCollection(CollectionMutation request, Actor actor, String requestId);
CollectionDefinition addSkill(String collectionId, String skillId, Actor actor, String requestId);
CollectionDefinition removeSkill(String collectionId, String skillId, Actor actor, String requestId);
void deactivateCollection(String collectionId, Actor actor, String requestId);
PlatformPolicy updatePolicy(PolicyMutation request, Actor actor, String requestId);
```

- [ ] **Step 1: Add service tests for role and validation boundaries**

  Cover admin success, non-admin `ForbiddenException`, duplicate team/category/tag/collection IDs as `ConfigConflictException`, inactive team references, invalid role, invalid SemVer, malformed page-size options, and policy version increment.

- [ ] **Step 2: Add tests for collection and taxonomy rules**

  Assert collection member add/remove is idempotent, missing Skill returns `SkillNotFoundException`, withdrawn Skill returns `InvalidLifecycleRequestException`, inactive team cannot own a new team-visible collection, and deactivation preserves historical references.

- [ ] **Step 3: Run focused tests and verify failure**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceConfigurationServiceTest test`; expect missing service/exception implementations.

- [ ] **Step 4: Implement admin-only mutation service**

  Resolve the actor once, call `RoleGuard.require(actor, Set.of("admin"))`, validate the complete candidate configuration before calling `GovernanceStore.updateGovernanceConfiguration`, and create one audit event with object ID, action, reason summary, actor, role, requestId, and timestamp.

- [ ] **Step 5: Implement read scope and error mapping**

  Return all active dictionaries to reviewer/admin, active dictionaries plus public collections to viewer/maintainer, and only team-visible collections whose `ownerTeamId` matches the actor's active role binding. Map `ConfigConflictException` to 409 `CONFIG_CONFLICT`, `CollectionNotFoundException` to 404 `COLLECTION_NOT_FOUND`, and validation exceptions to 400 `INVALID_REQUEST`.

- [ ] **Step 6: Verify service behavior**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceConfigurationServiceTest test` and require all permission, validation, idempotency, audit, and conflict assertions to pass.

### Task 3: Admin and public governance APIs

**Files:**

- Create `apps/api/src/main/java/com/huawei/skillcenter/governance/AdminGovernanceController.java`
- Create `GovernanceCatalogController.java`
- Create `apps/api/src/test/java/com/huawei/skillcenter/governance/AdminGovernanceControllerTest.java`
- Create `GovernanceCatalogControllerTest.java`

**Interfaces:**

- Admin routes: `/api/v1/admin/teams`, `/api/v1/admin/role-bindings`, `/api/v1/admin/taxonomy/categories`, `/api/v1/admin/taxonomy/tags`, `/api/v1/admin/collections`, `/api/v1/admin/policies` with the CRUD and member routes defined in the approved spec.
- Public routes: `GET /api/v1/governance/taxonomy`, `GET /api/v1/collections`, and `GET /api/v1/collections/{collectionId}`.
- Every response uses `ApiResponse<T>` and the request ID from `RequestIdFilter`.

- [ ] **Step 1: Write MockMvc contract tests**

  Test admin create/update/deactivate for one team, category, tag, collection, role binding, and policy; assert response shape, request ID, status codes, and stable errors for viewer write, duplicate ID, invalid body, and team-visibility denial.

- [ ] **Step 2: Run controller tests and verify failure**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=AdminGovernanceControllerTest,GovernanceCatalogControllerTest test`; expect missing controllers/routes.

- [ ] **Step 3: Implement controller mappings**

  Parse `Actor` with `ActorResolver`, pass `requestId` to every mutation, keep path variables URL-safe, and map service records to DTO views without exposing internal snapshot structure.

- [ ] **Step 4: Implement public paging and visibility**

  Apply `PlatformPolicy.maxPageSize` before slicing collections; return `INVALID_REQUEST` for page sizes outside configured options/upper bound and `COLLECTION_NOT_FOUND` for missing, inactive, or unauthorized team collections.

- [ ] **Step 5: Verify the API contracts**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=AdminGovernanceControllerTest,GovernanceCatalogControllerTest test`; all routes must pass status, envelope, request ID, role, conflict, and visibility assertions.

### Task 4: Catalog, market, and collection integration

**Files:**

- Create `apps/api/src/main/java/com/huawei/skillcenter/skill/CollectionService.java`
- Modify `SkillCatalogService.java`, `SkillController.java`, and `SkillQuery.java` only where needed for active taxonomy/policy filtering.
- Create `apps/api/src/test/java/com/huawei/skillcenter/skill/GovernanceCatalogIntegrationTest.java`

**Interfaces:**

- `CollectionService.list(Actor actor, int page, int pageSize)` returns active visible `CollectionDefinition` summaries.
- `CollectionService.detail(String collectionId, Actor actor)` returns a visible collection with resolved active Skill summaries.
- `SkillCatalogService` keeps existing `list(SkillQuery)` and `detail(skillId)` signatures; category filters use active governance definitions when configured and continue accepting legacy seed categories.

- [ ] **Step 1: Write integration tests**

  Seed active/inactive categories, a public collection, a team collection, and one withdrawn Skill; assert taxonomy response excludes inactive items, public users see public collections only, team members see their collection, withdrawn Skills cannot be added or returned as new collection members, and existing market/detail responses remain valid.

- [ ] **Step 2: Run integration tests and verify failure**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceCatalogIntegrationTest test`; expect missing collection service and governance filtering.

- [ ] **Step 3: Implement collection read service**

  Resolve collection visibility from the role binding, filter inactive members, map each remaining ID through the existing catalog service, and preserve the collection definition even when a historical member is no longer visible.

- [ ] **Step 4: Apply taxonomy and policy to catalog reads**

  Expose active categories/tags through the governance taxonomy endpoint, keep legacy category matching case-insensitive, and enforce policy page-size bounds without changing existing `SkillPageResponse` fields.

- [ ] **Step 5: Verify integration and regressions**

  Run `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceCatalogIntegrationTest,SkillControllerTest,SkillCatalogGovernanceTest test`.

### Task 5: Frontend governance workbench and collections page

**Files:**

- Modify `apps/web/src/api/skillApi.js` with governance admin/public methods.
- Create `apps/web/src/GovernanceSettingsView.jsx`.
- Create `apps/web/src/CollectionsView.jsx`.
- Create `apps/web/src/governance.js` with pure helpers for form validation, status labels, optimistic list updates, and conflict messages.
- Modify `apps/web/src/App.jsx`, `apps/web/src/styles.css`.
- Modify `apps/web/tests/api-client.test.mjs`; create `apps/web/tests/governance.test.mjs`.

**Interfaces:**

- API client methods: `listTeams`, `saveTeam`, `deactivateTeam`, `listRoleBindings`, `saveRoleBinding`, `deactivateRoleBinding`, `listCategories`, `saveCategory`, `deactivateCategory`, `listTags`, `saveTag`, `deactivateTag`, `listCollections`, `saveCollection`, `deactivateCollection`, `addCollectionSkill`, `removeCollectionSkill`, `getPolicy`, `savePolicy`, `getTaxonomy`, and `listPublicCollections`.
- `GovernanceSettingsView({ api, role, onToast })` renders tabs `teams`, `roles`, `taxonomy`, `collections`, `policy`; non-admin controls are read-only.
- `CollectionsView({ api, onOpenSkill })` renders public/team-visible collections and member Skill links.

- [ ] **Step 1: Add API client tests**

  Assert URL encoding, query parameters, HTTP methods, JSON bodies, and the existing actor headers for every new method; include public taxonomy and collection reads.

- [ ] **Step 2: Run frontend tests and verify failure**

  Run `npm.cmd test --prefix apps/web`; expect missing client methods/helpers.

- [ ] **Step 3: Implement API client and pure helpers**

  Reuse `queryString`, keep all paths under `/api/v1`, and make helper functions deterministic: `validateGovernanceForm`, `upsertById`, `removeById`, `formatConfigError`.

- [ ] **Step 4: Implement the settings workbench**

  Use one focused component per tab section, local form state, disabled non-admin inputs, loading/empty/error/conflict states, optimistic updates only for idempotent member operations, and rollback on failed writes.

- [ ] **Step 5: Replace placeholders and wire market filters**

  Route the existing settings view to `GovernanceSettingsView`, route the top “技能合集” view to `CollectionsView`, load taxonomy values for market filters, and preserve existing market/detail/review/install/analytics navigation.

- [ ] **Step 6: Add frontend behavior tests and verify**

  Run `npm.cmd test --prefix apps/web`; then run `npm.cmd run build --prefix apps/web` and require the Vite + Sites bundle to complete successfully.

### Task 6: Documentation, end-to-end smoke, and M5.1 status

**Files:**

- Create `docs/project/M5.1-governance-configuration-status.md`.
- Update `docs/project/M4.3-lifecycle-personal-center-status.md` with the M5.1 boundary link.
- Update `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md` M5 checklist entries that are actually delivered; leave M5.2+ items unchecked.

- [ ] **Step 1: Run the complete verification suite**

  Run `mvn -B -q -f apps/api/pom.xml test`, `npm.cmd test --prefix apps/web`, and `npm.cmd run build --prefix apps/web`; record test counts and failures in the status report.

- [ ] **Step 2: Run live API smoke**

  With admin actor, create a team, category, tag, public collection, and policy; add an existing published Skill; read the public taxonomy/collection endpoints as viewer; read a team collection as a bound member and deny it to an unrelated viewer; restart the API and verify the configuration remains.

- [ ] **Step 3: Scan for documentation placeholders and compatibility gaps**

  Scan the plan and status report for unresolved placeholder text; verify no new route removes a legacy field or exposes sensitive payloads.

- [ ] **Step 4: Write the status report**

  Document delivered endpoints, audit actions, defaults, verification evidence, local identity limitations, and explicit M5.2 follow-up boundaries (controlled export/audit integrity/retention/security/observability).

**Completion standard:** Tasks 1–5 pass their focused tests, full backend/frontend verification passes, the live smoke proves configuration survives restart and respects public/team visibility, and the M5.1 status report clearly separates delivered governance configuration from remaining M5 work.
