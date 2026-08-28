# A4.2 OpenSearch Search Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-closed OpenSearch/Elasticsearch-compatible HTTP implementation behind `SkillSearchIndex` without changing JSON/PostgreSQL defaults or catalog authorization.

**Architecture:** `HttpSkillSearchIndex` owns bounded HTTP request/response handling and translates only the existing search document/hit contract. Configuration and conditional bean wiring select the adapter explicitly; `SkillSearchBackendReadinessService` and an admin probe service expose safe readiness evidence. The existing coordinator continues to build snapshots and publish refreshes, so the external index remains a projection and never becomes the Skill source of truth.

**Tech Stack:** Java 21, Spring Boot 3.4, JDK `HttpClient`, Jackson, JUnit 5, Mockito, AssertJ, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-28-opensearch-search-adapter-design.md`

## Global Constraints

- `json` remains the default search backend and must not require external services.
- `opensearch` is explicit and must fail closed on invalid configuration, unavailable service, malformed responses, or expired probe evidence.
- Search payloads contain only bounded Skill metadata; no Prompt, Skill body, inputs/outputs, tool arguments, paths, credentials, tokens, Trace, or raw exceptions.
- Existing `SkillSearchIndex`, `SkillSearchRefreshCoordinator`, catalog authorization, pagination, and refresh outbox semantics remain compatible.
- All remote response bodies are bounded before JSON parsing; redirects are disabled; only stable reason codes leave the adapter boundary.

### Task 1: Freeze HTTP adapter contracts with failing tests

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/HttpSkillSearchIndex.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/search/HttpSkillSearchIndexTest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexStatus.java` only if an existing safe state is insufficient

**Interfaces:**
- `HttpSkillSearchIndex(String endpoint, String index, String credentialRef, Duration connectTimeout, Duration requestTimeout, int maxResponseBytes, HttpClient client, ObjectMapper mapper, Clock clock)`
- `SkillSearchIndex.backend()` returns `opensearch`.
- `rebuild`, `search`, `invalidate`, and `status` retain the existing interface signatures.

- [ ] **Step 1: Write a failing test for valid query translation and hit parsing**

```java
@Test
void parsesOnlyAllowlistedSearchHits() {
    // Use a local HttpServer fixture that returns hits._id, hits._score and matched fields.
    // Assert the adapter returns SkillSearchHit values and ignores a harmless unknown JSON field.
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=HttpSkillSearchIndexTest" test`  
Expected: compilation failure because `HttpSkillSearchIndex` does not exist.

- [ ] **Step 3: Implement the minimal bounded request/response adapter**

Implement GET/POST request construction with redirects disabled, bounded byte reads, stable non-2xx/timeout/invalid-response exceptions, explicit bearer secret resolution, and allowlisted parsing. Rebuild and search must reject blank endpoint/index before network access.

- [ ] **Step 4: Run the focused test to verify GREEN**

Run the same Maven command. Expected: valid hit parsing passes without exposing unknown response fields.

### Task 2: Add rebuild, filters, limits, and fail-closed behavior

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/search/HttpSkillSearchIndex.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/search/HttpSkillSearchIndexTest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java` if a new stable exception needs mapping

**Interfaces:**
- Bulk rebuild sends bounded NDJSON to `/{index}/_bulk` and returns `SkillSearchRebuildResult`.
- Search sends `SkillSearchQuery` filters to `/{index}/_search` and returns at most 5000 hits.

- [ ] **Step 1: Add failing tests for bounded bulk payload and error mapping**

```java
@Test
void rejectsOversizedResponseWithStableReasonCode() { }

@Test
void preservesPreviousStatusWhenBulkRebuildFails() { }
```

- [ ] **Step 2: Run the focused tests to verify RED**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=HttpSkillSearchIndexTest" test`  
Expected: assertions fail because oversized responses and failed rebuilds are not yet handled by the adapter.

- [ ] **Step 3: Implement minimal state and error behavior**

Keep the last successful status/document count/source hash in memory, mark `DEGRADED` on failed rebuild or invalidate, and never replace it with partial remote results. Validate `SkillSearchHit` construction and the 5000 candidate cap before returning.

- [ ] **Step 4: Run focused and existing search tests**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=HttpSkillSearchIndexTest,SkillSearch*" test`  
Expected: all focused tests pass and JSON/PostgreSQL search contracts remain green.

### Task 3: Add explicit configuration, bean selection, and readiness

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchCatalogConfiguration.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchBackendCondition.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/persistence/PersistenceControlProperties.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/SkillSearchBackendReadinessService.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchCatalogConfigurationTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/operations/SkillSearchBackendReadinessServiceTest.java`

**Interfaces:**
- `skill-center.search-index-backend=opensearch` selects exactly one `HttpSkillSearchIndex`.
- `skill-center.search-index.*` contains endpoint, index, credential-ref, bounded timeouts, and response limit.
- Readiness reports `opensearch`, `READY` only after a valid fresh probe and `NOT_READY` with stable codes otherwise.

- [ ] **Step 1: Add failing selector/readiness tests**

Assert `json` remains the default, `opensearch` is explicit, and an unconfigured or invalid OpenSearch backend returns `SEARCH_INDEX_ENDPOINT_NOT_CONFIGURED` or `SEARCH_INDEX_BACKEND_INVALID` without constructing a fallback bean.

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchCatalogConfigurationTest,SkillSearchBackendReadinessServiceTest" test`  
Expected: failure because the selector and properties do not recognize `opensearch`.

- [ ] **Step 3: Implement conditional wiring and safe properties**

Add an explicit condition, constructor wiring, bounded property normalization, and safe readiness mapping. Preserve all existing PostgreSQL event/readiness gates for the `postgresql` branch.

- [ ] **Step 4: Run focused configuration/readiness tests**

Run the same Maven command. Expected: JSON/PostgreSQL tests and new OpenSearch selector tests pass.

### Task 4: Add admin probe evidence and local dependency wiring

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchConnectivityProbeService.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchConnectivityProbeServiceTest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/PlatformReadinessService.java`
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/OperationsMetricsView.jsx`
- Modify: `apps/web/tests/operations-view.test.mjs`
- Modify: `deploy/local/compose.yaml`
- Modify: `deploy/local/README.md`
- Modify: `deploy/local/.env.example`
- Modify: `docs/project/environment-dependencies.md`

**Interfaces:**
- `POST /api/v1/admin/search/index/probe` returns only backend/status/reasonCode/httpStatus/latency/checkedAt.
- Probe never reads or indexes Skill content; only a fresh `REACHABLE` result can make external search readiness `READY`.
- Local Compose adds a pinned OpenSearch single-node service for development only, bound to loopback with a healthcheck and no production claims.

- [ ] **Step 1: Add failing controller/client/compose contract tests**

Assert the API rejects non-admin probes, the response has no endpoint or body, the Web client calls the exact path, and Compose contains a healthcheck and loopback binding.

- [ ] **Step 2: Run focused tests to verify RED**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchIndexControllerTest,SkillSearchConnectivityProbeServiceTest" test`; then run `npm.cmd test -- tests/operations-view.test.mjs`.  
Expected: new probe and Compose assertions fail before implementation.

- [ ] **Step 3: Implement probe, audit-safe recovery, client, UI and local service**

Reuse the existing actor/request-id and redaction patterns. Keep local default behavior unchanged and do not enable OpenSearch automatically.

- [ ] **Step 4: Run focused API/Web tests**

Run the same Maven command and `npm.cmd test -- tests/operations-view.test.mjs`. Expected: all focused tests pass.

### Task 5: Full verification and scoped commit

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/project/environment-dependencies.md`

- [ ] **Step 1: Run API regression**

Run in `apps/api`: `mvn.cmd -q -DforkCount=0 test`. Aggregate all `target/surefire-reports/TEST-*.xml` and require zero failures/errors; report Docker capability skips separately.

- [ ] **Step 2: Run Web regression and build**

Run in `apps/web`: `npm.cmd test` and `npm.cmd run build`. Require exit code 0.

- [ ] **Step 3: Run repository checks**

Run from the repository root: `git diff --check` and validate staged names contain only the A4.2 implementation plus its tests/config/docs.

- [ ] **Step 4: Commit the scoped implementation**

```powershell
git add apps/api/src/main/java apps/api/src/test/java apps/api/src/main/resources/application.yml apps/web/src deploy/local docs/project/environment-dependencies.md docs/project/M11-external-integration-runbook.md docs/project/remaining-coding-tasks-status.md
git commit -m "feat: add external skill search adapter"
```

