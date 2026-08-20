# Skill Center M2 Real Backend Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the M1 runtime mock catalog with a runnable Spring Boot API and connect the market, detail, upload, installation, and analytics flows to real HTTP endpoints.

**Architecture:** Add `apps/api` as a Maven Spring Boot 3 service. Keep domain services independent from persistence by defining repository/storage interfaces with in-memory and local-file implementations for M2. Expose versioned REST endpoints under `/api/v1`, validate uploaded packages and M0 event payloads at the API boundary, and let the React app consume those endpoints through a Vite `/api` proxy.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Maven, Jackson, Spring Web, Spring Boot Validation, JUnit 5, MockMvc, React/Vite, Node built-in test runner.

## Global Constraints

- M0 JSON Schemas remain immutable and are loaded from `contracts/schemas/v1/`.
- Skill packages are uploaded as local ZIP files; the website does not create or edit Skill source files and has no draft state.
- Invocation events accept only the M0 allow-listed fields; prompt, output, file content, credentials, and bearer tokens are rejected.
- Upload validation rejects path traversal, absolute paths, symbolic-link entries, nested ZIP files, packages over 20 MiB, and decompressed content over 100 MiB.
- API errors use `{error:{code,message,details},requestId}` and never include package contents or sensitive payloads.
- M2 persistence is in-memory/local-file only; PostgreSQL, S3, Kafka, Redis, OpenSearch, SSO, and production TLS are out of scope.
- Run commands from `D:\2026.8\SkillCenter` unless a task says otherwise.

---

### Task 1: Scaffold the API and define the HTTP error envelope

**Files:**
- Create: `apps/api/pom.xml`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/SkillCenterApiApplication.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/api/ApiResponse.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/api/ApiError.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/api/RequestIdFilter.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Create: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/api/ApiErrorContractTest.java`

**Interfaces:**
- `ApiResponse<T>` exposes `data` and `requestId` for successful responses.
- `ApiError` exposes `error.code`, `error.message`, `error.details`, and `requestId`.
- `RequestIdFilter` reads a valid incoming `X-Request-Id` or creates a UUID and exposes it to controllers.

- [ ] **Step 1: Write the failing test**

```java
@Test
void unknownRouteReturnsStableErrorEnvelope() throws Exception {
  mockMvc.perform(get("/api/v1/does-not-exist"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
      .andExpect(jsonPath("$.requestId").isNotEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f apps/api/pom.xml -Dtest=ApiErrorContractTest test`  
Expected: FAIL because `apps/api` and the Spring application do not exist.

- [ ] **Step 3: Write minimal implementation**

Create the Maven project with `spring-boot-starter-web`, `spring-boot-starter-validation`, and `spring-boot-starter-test`; add the application class, response records, request-id filter, and exception handler for 404/400/500.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f apps/api/pom.xml -Dtest=ApiErrorContractTest test`  
Expected: PASS.

---

### Task 2: Add the Skill catalog domain and list/detail endpoints

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillSummary.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillDetail.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillRepository.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/skill/InMemorySkillRepository.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillCatalogService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillController.java`
- Create: `apps/api/src/main/resources/skills.json`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/skill/SkillControllerTest.java`

**Interfaces:**
- `SkillRepository.findPublished(SkillQuery query): PageResult<SkillSummary>`.
- `SkillRepository.findDetail(String skillId): Optional<SkillDetail>`.
- `SkillController` serves `GET /api/v1/skills` and `GET /api/v1/skills/{skillId}`.
- `SkillQuery` supports `query`, `category`, `status`, `risk`, `page`, and `pageSize`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void listFiltersByCategoryAndReturnsPageMetadata() throws Exception {
  mockMvc.perform(get("/api/v1/skills").param("category", "网络运维"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items").isArray())
      .andExpect(jsonPath("$.data.items[0].category").value("网络运维"))
      .andExpect(jsonPath("$.data.page").value(1))
      .andExpect(jsonPath("$.data.pageSize").value(12));
}

@Test
void missingSkillReturnsNotFoundCode() throws Exception {
  mockMvc.perform(get("/api/v1/skills/missing"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error.code").value("SKILL_NOT_FOUND"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f apps/api/pom.xml -Dtest=SkillControllerTest test`  
Expected: FAIL because the Skill controller and repository do not exist.

- [ ] **Step 3: Write minimal implementation**

Load 12 M1 seed records from `skills.json`, implement case-insensitive text/category/status/risk filtering, clamp `pageSize` to 50, and return detail data with capabilities, examples, permissions, collections, and metrics.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f apps/api/pom.xml -Dtest=SkillControllerTest test`  
Expected: PASS.

---

### Task 3: Implement secure ZIP package validation and upload endpoint

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageValidationResult.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageValidationService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/LocalPackageStorage.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageController.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageValidationServiceTest.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageControllerTest.java`
- Modify: `apps/api/src/main/resources/application.yml`

**Interfaces:**
- `PackageValidationService.validate(Path zip): PackageValidationResult`.
- `LocalPackageStorage.save(MultipartFile file, String packageId): StoredPackage`.
- `POST /api/v1/skill-packages` accepts multipart field `file` and returns `201` with package status.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void rejectsPathTraversalEntry() throws Exception {
  Path zip = fixtureZip("skill-id", Map.of("../escape.txt", "blocked"));
  assertThat(service.validate(zip).errors()).anyMatch(error -> error.code().equals("UNSAFE_PATH"));
}

@Test
void uploadReturnsValidatedPackageForCanonicalExample() throws Exception {
  MockMultipartFile file = examplePackage();
  mockMvc.perform(multipart("/api/v1/skill-packages").file(file))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.data.status").value("validated"))
      .andExpect(jsonPath("$.data.skillId").value("summarize-release-notes"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -f apps/api/pom.xml -Dtest=PackageValidationServiceTest,PackageControllerTest test`  
Expected: FAIL because package validation and upload controller do not exist.

- [ ] **Step 3: Write minimal implementation**

Inspect ZIP entries before extraction, normalize paths, reject unsafe entries and nested archives, enforce compressed and decompressed limits, require exactly one safe root, parse `skill.json`, verify required `SKILL.md` frontmatter, and store only validated bytes under `data/packages/{packageId}.zip`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -f apps/api/pom.xml -Dtest=PackageValidationServiceTest,PackageControllerTest test`  
Expected: PASS.

---

### Task 4: Add installation manifest generation

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/InstallationRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/InstallManifest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionController.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/distribution/DistributionControllerTest.java`

**Interfaces:**
- `DistributionService.createManifest(String skillId, InstallationRequest request): InstallManifest`.
- `POST /api/v1/skills/{skillId}/installations` returns `201` with an install-manifest-compatible payload.

- [ ] **Step 1: Write the failing test**

```java
@Test
void installationResponseContainsHashAndCompatibility() throws Exception {
  mockMvc.perform(post("/api/v1/skills/eox-query/installations")
          .contentType(APPLICATION_JSON)
          .content("{\"clientType\":\"codex\",\"clientVersion\":\"1.0.0\",\"method\":\"one-click\"}"))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.data.skill.id").value("eox-query"))
      .andExpect(jsonPath("$.data.artifact.sha256").value(matchesPattern("[0-9a-f]{64}")))
      .andExpect(jsonPath("$.data.compatibility").exists());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f apps/api/pom.xml -Dtest=DistributionControllerTest test`  
Expected: FAIL because the distribution service and controller do not exist.

- [ ] **Step 3: Write minimal implementation**

Build the manifest from the published catalog record and the seeded package artifact, compute a lowercase SHA-256, emit HTTPS-compatible local URLs from configuration, and set a bounded expiration time. Reject unpublished or unknown Skill IDs with `INSTALLATION_NOT_AVAILABLE`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f apps/api/pom.xml -Dtest=DistributionControllerTest test`  
Expected: PASS.

---

### Task 5: Add invocation event ingestion and analytics aggregation

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEvent.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEventService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEventController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsOverview.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsController.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/events/InvocationEventServiceTest.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/analytics/AnalyticsControllerTest.java`

**Interfaces:**
- `InvocationEventService.ingest(InvocationEvent event): IngestResult`.
- `GET /api/v1/analytics/overview` returns `kpis`, `series`, and `topSkills`.
- `POST /api/v1/events/invocations` returns `202` for new event and `200` for duplicate `eventId`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void duplicateEventIdDoesNotIncrementInvocationCountTwice() {
  InvocationEvent event = validEvent("event-1", "eox-query");
  assertThat(service.ingest(event).duplicate()).isFalse();
  assertThat(service.ingest(event).duplicate()).isTrue();
  assertThat(service.totalCalls("eox-query")).isEqualTo(1);
}

@Test
void analyticsOverviewContainsSevenDaySeries() throws Exception {
  mockMvc.perform(get("/api/v1/analytics/overview"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.kpis.calls").isNumber())
      .andExpect(jsonPath("$.data.series").isArray())
      .andExpect(jsonPath("$.data.topSkills").isArray());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -f apps/api/pom.xml -Dtest=InvocationEventServiceTest,AnalyticsControllerTest test`  
Expected: FAIL because event ingestion and analytics services do not exist.

- [ ] **Step 3: Write minimal implementation**

Deserialize only the allow-listed M0 event fields, validate required conditional fields (`errorCode` for failure/timeout), deduplicate by `eventId`, aggregate calls/successes by day and Skill, and return the UI-shaped overview response without storing content fields.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -f apps/api/pom.xml -Dtest=InvocationEventServiceTest,AnalyticsControllerTest test`  
Expected: PASS.

---

### Task 6: Connect the React app to the real API

**Files:**
- Create: `apps/web/src/api/client.js`
- Create: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/src/state.js`
- Modify: `apps/web/vite.config.mjs`
- Modify: `apps/web/tests/state.test.mjs`
- Create: `apps/web/tests/api-client.test.mjs`

**Interfaces:**
- `apiClient.request(path, options): Promise<any>`.
- `skillApi.listSkills(params)`, `getSkill(id)`, `uploadPackage(file)`, `createInstallation(id, input)`, `getAnalyticsOverview(params)`, and `ingestInvocation(event)`.
- `App` owns `skills`, `selectedSkill`, `platformMetrics`, and `analyticsData` loaded from these functions.

- [ ] **Step 1: Write the failing test**

```js
test("skillApi.listSkills calls the versioned skills endpoint", async () => {
  const calls = [];
  const api = createSkillApi((path) => { calls.push(path); return Promise.resolve({ data: { items: [] } }); });
  await api.listSkills({ query: "EOX", page: 1, pageSize: 12 });
  assert.equal(calls[0], "/api/v1/skills?query=EOX&page=1&pageSize=12");
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm.cmd test -- tests/api-client.test.mjs` from `apps/web`  
Expected: FAIL because the API client modules do not exist.

- [ ] **Step 3: Write minimal implementation**

Implement fetch with a 10-second timeout, JSON error parsing, and multipart upload support; replace runtime imports of mock catalog/metrics in `App.jsx` with effects calling the API, while retaining pure state helpers for filtering/formatting tests. Add Vite proxy configuration and explicit loading/error states.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm.cmd test` from `apps/web`  
Expected: PASS.

---

### Task 7: Run full contract, build, and browser integration verification

**Files:**
- Create: `docs/project/M2-real-backend-integration-status.md`
- Modify: `apps/web/README.md`
- Modify: `design-qa.md`

- [ ] **Step 1: Run backend tests**

Run: `mvn -q -f apps/api/pom.xml test`  
Expected: all Spring/JUnit tests pass.

- [ ] **Step 2: Run frontend tests and build**

Run: `npm.cmd test` and `npm.cmd run build` from `apps/web`  
Expected: all Node tests pass and Sites build artifacts are generated.

- [ ] **Step 3: Run M0 contract regression**

Run: `$python = 'C:\Users\admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'; $env:PYTHONPATH = (Resolve-Path .\\.vendor).Path; & $python -m unittest discover -s tests -p 'test_*.py' -v`  
Expected: 11 M0 contract tests pass.

- [ ] **Step 4: Run local integration**

Start API with `mvn -f apps/api/pom.xml spring-boot:run`, start Web with `npm.cmd run dev -- --host 127.0.0.1`, then verify market load, detail load, invalid/valid upload, install manifest, invocation event ingestion, analytics overview, and API-down error state in the browser.

- [ ] **Step 5: Record evidence**

Update `docs/project/M2-real-backend-integration-status.md` and `design-qa.md` with commands, response evidence, screenshots, viewport, and any remaining P3 follow-up.

