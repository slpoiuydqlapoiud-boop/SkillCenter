# M3 Skill Governance Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the M2 local Skill catalog into a restart-safe governance loop with version states, review actions, RBAC, installation records, audit events, and real management views.

**Architecture:** Keep M2 REST paths and response envelopes stable. Add a thread-safe JSON file-backed `GovernanceStore` for package versions, review tasks, installations, and audit events; use an actor resolver over `X-User-Role` with a local admin default. Public catalog reads only published versions, while admin/reviewer endpoints operate on pending review records. Extend the React API client and replace the review/install placeholders without introducing external infrastructure.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Jackson, JUnit 5, MockMvc, React 19, Vite, Node built-in test runner.

## Global Constraints

- Skill source is created only by local ZIP upload; the website never creates or edits Skill source and has no draft state.
- Governance persistence is local JSON at `skill-center.governance-storage` (default `./data/governance/state.json`) with atomic temp-file replacement.
- Version statuses are `pending_review`, `security_review`, `published`, `rejected`, and `withdrawn`; high-risk versions require the intermediate security review state.
- Public catalog and installation manifests expose only `published` versions.
- Product roles are `developer` and `admin`; `viewer`, `maintainer`, and `reviewer` remain compatibility aliases; missing `X-User-Role` defaults to `admin` for local development. Security review uses a different `admin` identity.
- API errors remain `{error:{code,message,details},requestId}`; permission failures use `FORBIDDEN`, state conflicts use `REVIEW_STATE_CONFLICT`, and persistence failures use `PERSISTENCE_FAILED`.
- Audit metadata is allow-listed identifiers/status only; prompt, output, file contents, credentials, and bearer tokens are never stored.
- M0 contract tests and all M2 tests must continue to pass.

---

### Task 1: Add governance domain records and restart-safe JSON store

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/SkillVersion.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewTask.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/InstallationRecord.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/AuditEvent.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceSnapshot.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/GovernanceStoreTest.java`

**Interfaces:**
- `GovernanceStore.snapshot(): GovernanceSnapshot` returns an immutable snapshot.
- `GovernanceStore.createPendingVersion(SkillVersion version, ReviewTask review, AuditEvent audit): GovernanceSnapshot` atomically appends the three records.
- `GovernanceStore.updateReview(String reviewId, ReviewTask review, SkillVersion version, AuditEvent audit): GovernanceSnapshot` atomically updates the state transition.
- `GovernanceStore.addInstallation(InstallationRecord record, AuditEvent audit): GovernanceSnapshot` persists installation and audit records together.
- `GovernanceStore.reload(): void` reloads the JSON file for restart tests.

- [ ] **Step 1: Write the failing test**

```java
@Test
void pendingReviewSurvivesStoreReload() {
    GovernanceStore first = storeAt(tempDir.resolve("state.json"));
    first.createPendingVersion(version("package-1"), review("review-1"), audit("PACKAGE_UPLOADED"));

    GovernanceStore restarted = storeAt(tempDir.resolve("state.json"));

    assertThat(restarted.snapshot().versions()).extracting(SkillVersion::status)
            .containsExactly("pending_review");
    assertThat(restarted.snapshot().reviews()).extracting(ReviewTask::reviewId)
            .containsExactly("review-1");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceStoreTest test`

Expected: FAIL because governance records and `GovernanceStore` do not exist.

- [ ] **Step 3: Write minimal implementation**

Create Java records with explicit status strings and UTC `Instant` timestamps. The store loads an existing JSON snapshot or writes an empty snapshot; use a `ReentrantReadWriteLock`, `Files.createDirectories`, a sibling `.tmp` file, and `Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)` with a non-atomic fallback. Seed published versions for the existing `skills.json` catalog only when the state file is first created.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceStoreTest test`

Expected: PASS, including a second store instance loading the same file.

---

### Task 2: Add actor resolution and RBAC enforcement

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/Actor.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/ActorResolver.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/ForbiddenException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/RequiresRole.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/security/RoleGuard.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/security/RoleGuardTest.java`

**Interfaces:**
- `ActorResolver.resolve(HttpServletRequest): Actor` reads `X-User-Id` (default `local-user`) and `X-User-Role` (default `admin`).
- `RoleGuard.require(Actor actor, Set<String> allowedRoles): void` throws `ForbiddenException` for invalid or insufficient roles.
- Controllers use `RoleGuard` before upload/review/audit operations and pass the resolved actor to services.

- [ ] **Step 1: Write the failing test**

```java
@Test
void viewerCannotReview() {
    Actor viewer = new Actor("u-1", "viewer");

    assertThatThrownBy(() -> guard.require(viewer, Set.of("reviewer", "admin")))
            .isInstanceOf(ForbiddenException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=RoleGuardTest test`

Expected: FAIL because the actor and guard classes do not exist.

- [ ] **Step 3: Write minimal implementation**

Allow only the four configured roles, default missing headers for local development, and map `ForbiddenException` to HTTP 403 with code `FORBIDDEN`. Keep actor identity separate from Skill payloads.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=RoleGuardTest test`

Expected: PASS.

---

### Task 3: Persist upload versions as pending review and expose review APIs

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewStateConflictException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/ReviewControllerTest.java`

**Interfaces:**
- `ReviewService.submitValidatedPackage(PackageValidationResult result, StoredPackage stored, Actor actor, String requestId): ReviewSubmission`.
- `ReviewService.list(String status, Actor actor): List<ReviewTaskView>`.
- `ReviewService.approve(String reviewId, Actor actor, String requestId): ReviewTaskView`.
- `ReviewService.reject(String reviewId, String reason, Actor actor, String requestId): ReviewTaskView`.
- `GET /api/v1/admin/reviews?status=pending_review` returns reviewer-visible pending tasks.
- `POST /api/v1/admin/reviews/{reviewId}/approve` returns the approved task.
- `POST /api/v1/admin/reviews/{reviewId}/reject` accepts `{reason}` and returns the rejected task.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void uploadCreatesPendingReviewInsteadOfPublishedPackage() throws Exception {
    mockMvc.perform(multipart("/api/v1/skill-packages")
            .header("X-User-Role", "maintainer")
            .file(canonicalExample()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("pending_review"));
}

@Test
void viewerCannotApproveReview() throws Exception {
    mockMvc.perform(post("/api/v1/admin/reviews/review-1/approve")
            .header("X-User-Role", "viewer"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=ReviewControllerTest test`

Expected: FAIL because upload still returns `validated` and review endpoints do not exist.

- [ ] **Step 3: Write minimal implementation**

Move the successful upload response status to `pending_review`, create a `SkillVersion` and `ReviewTask`, and write `PACKAGE_UPLOADED`. On approval, require reviewer/admin and pending state, set version to published, set reviewer fields, write `REVIEW_APPROVED`; on rejection require a nonblank reason and write `REVIEW_REJECTED`. Return `REVIEW_STATE_CONFLICT` for repeat actions. Keep package bytes unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=ReviewControllerTest test`

Expected: PASS, including maintainer upload, viewer denial, approve, reject, and repeated transition cases.

---

### Task 4: Make public catalog and version history governance-aware

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/VersionController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/InMemorySkillRepository.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillCatalogService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillController.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/skill/GovernedCatalogTest.java`

**Interfaces:**
- `GET /api/v1/skills/{skillId}/versions` returns published and, for reviewer/admin, governance-visible version entries.
- Public `GET /api/v1/skills` and detail continue returning only published records.
- `SkillCatalogService.isPublished(String skillId, String version): boolean` is used by distribution.

- [ ] **Step 1: Write the failing test**

```java
@Test
void pendingVersionIsHiddenUntilReviewApproval() {
    submitPending("new-skill", "1.0.0");

    assertThat(publicCatalog.findDetail("new-skill")).isEmpty();

    approvePending("new-skill", "1.0.0");

    assertThat(publicCatalog.findDetail("new-skill")).isPresent();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=GovernedCatalogTest test`

Expected: FAIL because the catalog does not read governance state and has no version history endpoint.

- [ ] **Step 3: Write minimal implementation**

Use the seed catalog as the published baseline and overlay governance records by `skillId`/`version`. Do not expose pending or rejected uploads to viewer/maintainer public reads. Add a version view containing packageId, version, status, sha256, upload/publish timestamps, and reviewId; reviewer/admin can see all statuses through the version endpoint.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=GovernedCatalogTest test`

Expected: PASS.

---

### Task 5: Persist installation records and expose audit queries

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/InstallationService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/InstallationController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/AuditController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionController.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/InstallationAuditControllerTest.java`

**Interfaces:**
- `InstallationService.create(InstallManifest manifest, InstallationRequest request, Actor actor, String requestId): InstallationRecord`.
- `GET /api/v1/installations?status=&skillId=&page=&pageSize=` returns role-filtered records.
- `GET /api/v1/audit?action=&resourceType=&page=&pageSize=` is admin-only.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void installationCreatesQueryableRecordAndAuditEvent() throws Exception {
    mockMvc.perform(post("/api/v1/skills/eox-query/installations")
            .header("X-User-Id", "u-1")
            .contentType(APPLICATION_JSON)
            .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("requested"));

    mockMvc.perform(get("/api/v1/installations").header("X-User-Id", "u-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].skillId").value("eox-query"));

    mockMvc.perform(get("/api/v1/audit").header("X-User-Role", "admin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].action").value("INSTALLATION_CREATED"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=InstallationAuditControllerTest test`

Expected: FAIL because installation creation does not persist records and query endpoints do not exist.

- [ ] **Step 3: Write minimal implementation**

After manifest generation, write `InstallationRecord(status=requested)` and `INSTALLATION_CREATED` in one store transaction. Filter records by actor: viewer/maintainer own records, reviewer records for their team scope (M3 local implementation may use actorId), admin all. Return paginated results and fixed metadata only. Audit endpoint must return allow-listed metadata and reject non-admin actors with 403.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=InstallationAuditControllerTest test`

Expected: PASS.

---

### Task 6: Add frontend role headers and governance views

**Files:**
- Modify: `apps/web/src/api/client.js`
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/tests/api-client.test.mjs`
- Create: `apps/web/tests/governance-api.test.mjs`

**Interfaces:**
- `createApiClient({ getActor }): { request, setActor }` adds `X-User-Id` and `X-User-Role` to every API request.
- `skillApi.listReviews(status)`, `approveReview(reviewId)`, `rejectReview(reviewId, reason)`, `listInstallations(params)`, and `listAudit(params)` call the M3 endpoints.

- [ ] **Step 1: Write the failing tests**

```js
test("api client sends the selected role header", async () => {
  const calls = [];
  const client = createApiClient({
    fetchImpl: async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify({ data: {} })); },
    getActor: () => ({ userId: "u-1", role: "reviewer" }),
  });
  await client.request("/api/v1/admin/reviews");
  assert.equal(calls[0].options.headers["X-User-Role"], "reviewer");
});

test("skillApi.approveReview calls the review action endpoint", async () => {
  const calls = [];
  const api = createSkillApi((path) => { calls.push(path); return Promise.resolve({ data: {} }); });
  await api.approveReview("review-1");
  assert.equal(calls[0], "/api/v1/admin/reviews/review-1/approve");
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm.cmd test -- tests/api-client.test.mjs tests/governance-api.test.mjs` from `apps/web`

Expected: FAIL because role-aware client methods and governance API methods do not exist.

- [ ] **Step 3: Write minimal implementation**

Keep the existing request timeout/error envelope behavior, merge actor headers without overwriting explicit headers, and add the five API methods. In `App.jsx`, synchronize the selected role with `setActor`, replace review/install placeholders with table views, wire approve/reject actions and reason input, and show installation records. Do not expose audit data outside admin.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm.cmd test` and `npm.cmd run build` from `apps/web`

Expected: all frontend tests pass and the production build succeeds.

---

### Task 7: Full regression, restart verification, and documentation

**Files:**
- Create: `docs/project/M3-governance-loop-status.md`
- Modify: `apps/web/README.md`
- Modify: `design-qa.md`

- [ ] **Step 1: Run backend regression**

Run: `mvn -B -f apps/api/pom.xml test`

Expected: all M0/M2/M3 Spring tests pass.

- [ ] **Step 2: Run frontend and contract regression**

Run `npm.cmd test`, `npm.cmd run build` in `apps/web`, then run the existing Python M0 unittest command from the repository root.

Expected: frontend tests/build and all 11 M0 contract tests pass.

- [ ] **Step 3: Verify the governance flow over HTTP**

Start the API, upload the canonical package with `X-User-Role: maintainer`, confirm `pending_review`, list the review as reviewer, approve it, confirm the version becomes visible in the public catalog, create an installation, query installations and audit as admin, restart the API, and confirm the same records remain.

- [ ] **Step 4: Record evidence**

Document commands, status codes, role checks, restart persistence, endpoint samples, and remaining M4 boundaries in `docs/project/M3-governance-loop-status.md`; update the Web README and design QA notes to remove the “review/install placeholder” statement.
