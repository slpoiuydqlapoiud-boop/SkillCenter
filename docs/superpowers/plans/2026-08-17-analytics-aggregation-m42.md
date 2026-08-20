# M4.2 Analytics Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the analytics API and management dashboard with Beijing-time 7/30/90/custom range aggregation, dimension filters, installation conversion, version adoption, error/latency metrics, and process-local data-quality summaries.

**Architecture:** Parse and validate query parameters into an immutable `AnalyticsQuery`; keep aggregation in a stateless `AnalyticsAggregator` that consumes event, catalog, installation, and ingestion-summary inputs; keep `AnalyticsService` as the business boundary and `AnalyticsController` as the HTTP/actor boundary. Extend the existing response records without removing legacy fields, and drive the dashboard through a parameterized API client plus pure date/query helpers.

**Tech Stack:** Java 21, Spring Boot 3.4.5, JUnit 5, AssertJ, MockMvc, React 19, Vite 6, Node built-in test runner.

## Global Constraints

- Use `ZoneId.of("Asia/Shanghai")` for all natural-day calculations.
- `range` accepts only `7d`, `30d`, `90d`, and `custom`; default is `7d`.
- Custom `from`/`to` are inclusive `YYYY-MM-DD` dates with a maximum 90-day span.
- Preserve legacy `kpis.calls/successfulCalls/successRate/activeSkills`, `series.day/calls/successRate`, and `topSkills` fields.
- Do not add prompt, output, file content, credentials, session IDs, device IDs, or tokens to analytics responses or logs.
- Aggregation uses accepted, event-ID-deduplicated invocation events; installation conversion uses persisted installation records because installation event details are not durable in M4.1.
- Data-quality counters are process-local and must be labeled as local-runtime data in API/UI copy.
- Every production behavior change follows TDD: write a failing test, run it, implement the minimum, rerun the focused test, then run the relevant regression suite.
- This workspace has no Git repository; do not issue commit commands. Use focused test checkpoints and preserve the working-tree diff for handoff.

---

### Task 1: Add analytics query parsing and range validation

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsQuery.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsQueryParser.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/analytics/InvalidAnalyticsQueryException.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/analytics/AnalyticsQueryParserTest.java`

**Interfaces:**
- Produces `AnalyticsQueryParser.parse(String range, String from, String to, String skillId, String teamId, String clientType, LocalDate today)`.
- `AnalyticsQuery` exposes `rangeType()`, `from()`, `to()`, `skillId()`, `teamId()`, `clientType()`, and `zoneId()`; `RangeType` values are `DAYS_7`, `DAYS_30`, `DAYS_90`, `CUSTOM`.
- `InvalidAnalyticsQueryException` extends `IllegalArgumentException` and carries a stable message suitable for `INVALID_REQUEST`.

- [ ] **Step 1: Write failing parser tests**

```java
@Test
void defaultRangeCoversSevenInclusiveBeijingDays() {
    AnalyticsQuery query = parser.parse(null, null, null, null, null, null,
            LocalDate.of(2026, 8, 17));
    assertThat(query.from()).isEqualTo(LocalDate.of(2026, 8, 11));
    assertThat(query.to()).isEqualTo(LocalDate.of(2026, 8, 17));
    assertThat(query.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
}

@Test
void customRangeIsInclusiveAndRejectsMoreThanNinetyDays() {
    AnalyticsQuery query = parser.parse("custom", "2026-08-01", "2026-08-31",
            "eox-query", "network-team", "codex", LocalDate.of(2026, 8, 31));
    assertThat(query.from()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(query.to()).isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(query.skillId()).isEqualTo("eox-query");
    assertThatThrownBy(() -> parser.parse("custom", "2026-01-01", "2026-04-01", null, null, null,
            LocalDate.of(2026, 8, 17))).isInstanceOf(InvalidAnalyticsQueryException.class);
}
```

- [ ] **Step 2: Run the focused test and confirm the expected missing-type failure**

Run: `mvn -q -f apps/api/pom.xml -Dtest=AnalyticsQueryParserTest test`

Expected: FAIL because the query/parser types do not exist.

- [ ] **Step 3: Implement the immutable query and parser**

Use `DateTimeFormatter.ISO_LOCAL_DATE`, `ZoneId.of("Asia/Shanghai")`, `ChronoUnit.DAYS.between(from, to) + 1`, and reject non-custom `from`/`to`, reversed dates, invalid range values, blank dimensions, and spans above 90 days. For fixed ranges, calculate `from = today.minusDays(days - 1)` and `to = today`.

- [ ] **Step 4: Run focused and regression tests**

Run: `mvn -q -f apps/api/pom.xml -Dtest=AnalyticsQueryParserTest test`

Expected: PASS.

### Task 2: Track process-local invocation ingestion quality

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationIngestionStats.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEventService.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/events/InvocationEventServiceTest.java`

**Interfaces:**
- `InvocationIngestionStats` exposes immutable receipt entries with `eventId`, nullable `occurredAt`, `receivedAt`, and result enum `ACCEPTED`, `DUPLICATE`, `REJECTED`, plus `entries()`.
- `InvocationEventService.ingestionStats()` returns the current process snapshot.
- Existing `ingest` and `ingestBatch` response contracts remain unchanged.

- [ ] **Step 1: Write failing tests for accepted, duplicate, and rejected receipts**

```java
@Test
void ingestionStatsRetainsQualityOutcomeWithoutPayload() {
    UUID id = UUID.randomUUID();
    service.ingest(validEvent(id, "eox-query"));
    service.ingest(validEvent(id, "eox-query"));
    service.ingestBatch(new InvocationEventBatchRequest("batch-1", "1.0", List.of(invalidEvent())));

    assertThat(service.ingestionStats().entries())
            .extracting(InvocationIngestionStats.Entry::result)
            .containsExactly(InvocationIngestionStats.Result.ACCEPTED,
                    InvocationIngestionStats.Result.DUPLICATE,
                    InvocationIngestionStats.Result.REJECTED);
    assertThat(service.ingestionStats().entries().get(0).eventId()).isEqualTo(id);
    assertThat(service.ingestionStats().entries().get(0).toString()).doesNotContain("prompt", "output", "token");
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `mvn -q -f apps/api/pom.xml -Dtest=InvocationEventServiceTest test`

Expected: FAIL because `ingestionStats()` and the receipt types do not exist.

- [ ] **Step 3: Implement the bounded in-memory receipt list**

Record a receipt for every first acceptance, duplicate, conflict/schema rejection, and validation rejection. Capture only event ID, parseable `occurredAt`, `Instant.now()` as `receivedAt`, and result; cap the list at 10,000 entries by dropping the oldest entry. Keep event payloads out of the receipt and preserve existing idempotency behavior.

- [ ] **Step 4: Run event regression tests**

Run: `mvn -q -f apps/api/pom.xml -Dtest=InvocationEventServiceTest,InvocationEventControllerTest test`

Expected: PASS.

### Task 3: Expand analytics response models and implement deterministic aggregation

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsOverview.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsAggregator.java`
- Create: `apps/api/src/test/java/com/huawei/skillcenter/analytics/AnalyticsAggregatorTest.java`

**Interfaces:**
- `AnalyticsAggregator.aggregate(AnalyticsQuery query, List<SkillSummary> skills, Collection<InvocationEvent> events, List<InstallationRecord> installations, InvocationIngestionStats ingestionStats)` returns `AnalyticsOverview`.
- Extend `AnalyticsOverview` with `VersionAdoption`, `ErrorBreakdown`, `Latency`, and `DataQuality` records. Extend `Kpis` with `activeUsers`, `activeTeams`, `currentInstallations`, `installationSuccessRate`; extend `DayPoint` with `activeUsers` and `installations`.
- Keep legacy constructors available where existing tests or JSON fixtures use them.

- [ ] **Step 1: Write failing aggregation tests with fixed Beijing-boundary events**

```java
@Test
void aggregateUsesBeijingDayAndCalculatesExtendedMetrics() {
    AnalyticsOverview result = aggregator.aggregate(query("custom", "2026-08-16", "2026-08-17"),
            skills(), List.of(
                    event("2026-08-15T16:30:00Z", "eox-query", "1.2.0", "u1", "team-a", "success", 100, null),
                    event("2026-08-16T16:30:00Z", "eox-query", "1.2.0", "u2", "team-a", "success", 300, null),
                    event("2026-08-17T01:00:00Z", "eox-query", "1.1.0", "u1", "team-b", "timeout", 900, "UPSTREAM_TIMEOUT")),
            installations(), ingestionStats());

    assertThat(result.series()).extracting(AnalyticsOverview.DayPoint::calls).containsExactly(1L, 2L);
    assertThat(result.kpis().activeUsers()).isEqualTo(2);
    assertThat(result.kpis().activeTeams()).isEqualTo(2);
    assertThat(result.kpis().successRate()).isEqualTo(66.67);
    assertThat(result.latency().p50Ms()).isEqualTo(300);
    assertThat(result.latency().p95Ms()).isEqualTo(900);
    assertThat(result.errorBreakdown().get(0).errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
}
```

- [ ] **Step 2: Run the focused test to observe the model/aggregator failure**

Run: `mvn -q -f apps/api/pom.xml -Dtest=AnalyticsAggregatorTest test`

Expected: FAIL because extended records and the aggregator do not exist.

- [ ] **Step 3: Implement minimal deterministic aggregation**

Filter invocation events by inclusive `from/to`, dimensions, and `occurredAt` converted with `query.zoneId()`. Generate every date in the range. Use nearest-rank percentile index `ceil(p * n) - 1`, clamp to `[0, n - 1]`. Calculate active users/teams from nonblank subjects, version/error lists with stable tie-breakers, and current installations from `installed`/`installing` records independent of date range. Use `SkillSummary` names for top skills and zero-fill published skills when no events are available.

- [ ] **Step 4: Add ingestion quality and empty-data assertions**

Assert zero-safe rates and latency for empty events, custom ranges with no events, and data-quality counters filtered by parseable occurred date while preserving unparseable rejection visibility.

- [ ] **Step 5: Run focused and analytics regression tests**

Run: `mvn -q -f apps/api/pom.xml -Dtest=AnalyticsAggregatorTest,AnalyticsControllerTest test`

Expected: PASS after the controller compatibility test is updated in Task 4.

### Task 4: Wire query parameters, actor scope, and API error contract

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/analytics/AnalyticsController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/analytics/AnalyticsControllerTest.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/api/ApiErrorContractTest.java`

**Interfaces:**
- `AnalyticsService.overview()` remains as a default-7d compatibility method.
- Add `AnalyticsService.overview(AnalyticsQuery query, Actor actor)`.
- Controller endpoint signature accepts `@RequestParam(required=false) String range/from/to/skillId/teamId/clientType`, resolves `Actor`, and passes the parsed query to the service.
- Add a specific `@ExceptionHandler(InvalidAnalyticsQueryException.class)` returning HTTP 400 code `INVALID_REQUEST`; keep the existing `IllegalArgumentException` event error mapping unchanged.

- [ ] **Step 1: Add failing MockMvc tests for ranges, filters, Beijing day and invalid input**

```java
@Test
void overviewAcceptsThirtyDayRangeAndFilters() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/overview")
                    .param("range", "30d")
                    .param("skillId", "eox-query")
                    .header("X-User-Id", "admin")
                    .header("X-User-Role", "admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.series.length()", is(30)))
            .andExpect(jsonPath("$.data.kpis.activeUsers").isNumber());
}

@Test
void overviewRejectsInvalidCustomRangeWithInvalidRequest() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/overview")
                    .param("range", "custom")
                    .param("from", "2026-01-01")
                    .param("to", "2026-04-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
}
```

- [ ] **Step 2: Run the focused controller tests and verify the expected failures**

Run: `mvn -q -f apps/api/pom.xml -Dtest=AnalyticsControllerTest,ApiErrorContractTest test`

Expected: FAIL because the controller ignores query parameters and no analytics-specific error handler exists.

- [ ] **Step 3: Implement service/controller wiring and role filtering**

Inject `ActorResolver` into the controller. Keep `reviewer` and `admin` global; for `viewer` filter events and installations by actor user ID; for `maintainer` limit events to skills whose `SkillSummary.owner()` or `team()` matches the actor user ID. Apply explicit `skillId/teamId/clientType` filters after the role scope and return empty aggregates when the intersection is empty.

- [ ] **Step 4: Add the analytics-specific error handler and compatibility delegation**

Map `InvalidAnalyticsQueryException` to `INVALID_REQUEST` with request ID. Ensure `overview()` delegates to a parser using `LocalDate.now(query.zoneId())`, so the existing no-parameter endpoint still returns seven points.

- [ ] **Step 5: Run backend regression suite**

Run: `mvn -B -q -f apps/api/pom.xml test`

Expected: PASS.

### Task 5: Parameterize the web API and analytics dashboard

**Files:**
- Create: `apps/web/src/analytics.js`
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/src/styles.css`
- Create: `apps/web/tests/analytics.test.mjs`
- Modify: `apps/web/tests/api-client.test.mjs`
- Modify: `apps/web/package.json`

**Interfaces:**
- `skillApi.getAnalyticsOverview(params = {})` calls `/api/v1/analytics/overview` with nonempty query parameters.
- `analytics.js` exports `ANALYTICS_RANGES`, `buildAnalyticsParams(range, from, to, filters)`, `validateCustomRange(from, to)`, and `formatAnalyticsRange(range, from, to)`.
- `AnalyticsView` accepts `analytics`, `analyticsQuery`, `onAnalyticsQueryChange`, and `analyticsLoading` props; it renders legacy and extended metrics without assuming any new field exists.

- [ ] **Step 1: Write failing pure-helper and API-client tests**

```js
test("buildAnalyticsParams preserves custom dates and dimensions", () => {
  assert.deepEqual(buildAnalyticsParams("custom", "2026-08-01", "2026-08-31", {
    skillId: "eox-query", teamId: "network-team", clientType: "codex"
  }), { range: "custom", from: "2026-08-01", to: "2026-08-31", skillId: "eox-query", teamId: "network-team", clientType: "codex" });
});

test("skillApi.getAnalyticsOverview encodes range and filters", async () => {
  const calls = [];
  const api = createSkillApi((path) => { calls.push(path); return Promise.resolve({ data: {} }); });
  await api.getAnalyticsOverview({ range: "30d", skillId: "eox-query" });
  assert.equal(calls[0], "/api/v1/analytics/overview?range=30d&skillId=eox-query");
});
```

- [ ] **Step 2: Run web tests and confirm missing exports/API method**

Run: `npm.cmd test --prefix apps/web`

Expected: FAIL because the helper module and parameterized method do not exist.

- [ ] **Step 3: Implement pure query helpers and API client**

Reject incomplete custom dates, reversed dates, and spans above 90 days; return a stable error string for the view. Keep `queryString` responsible for URL encoding and omit empty/`all` filters.

- [ ] **Step 4: Implement controlled range/filter state in `App.jsx`**

Initialize `{range: "7d", from: "", to: "", skillId: "", teamId: "", clientType: ""}`. Fetch analytics whenever the normalized query changes, keep the last successful response during loading, and show an inline error instead of clearing the dashboard. Add range options 7/30/90/custom, date inputs for custom, Skill/team/client filters, and disable apply when custom validation fails.

- [ ] **Step 5: Render extended metrics and empty states**

Add KPI cards for active users, current installations, and installation success rate; add compact panels for version adoption, error breakdown, p50/p95 latency, and process-local data quality. Keep the existing trend and top-Skill table compatible with old responses. Display “本地运行时口径” beside data-quality counts and use an empty-state message when lists are empty.

- [ ] **Step 6: Add focused styles and run web verification**

Add responsive styles for the filter row, compact analytics grids, progress bars, and empty states without changing existing distribution styles.

Run: `npm.cmd test --prefix apps/web`

Expected: PASS.

### Task 6: Update status documentation and run end-to-end verification

**Files:**
- Create: `docs/project/M4.2-analytics-aggregation-status.md`
- Modify: `docs/project/M4.1-distribution-telemetry-status.md`

**Interfaces:**
- Status doc records delivered endpoint parameters, response fields, local-runtime limitations, test commands, and smoke results.

- [ ] **Step 1: Run all automated checks**

Run:

```text
mvn -B -q -f apps/api/pom.xml test
npm.cmd test --prefix apps/web
npm.cmd run build --prefix apps/web
```

Expected: all commands exit 0.

- [ ] **Step 2: Run live API smoke tests**

With the API on port 8080 and Vite proxy on port 5173, verify:

```text
GET /api/v1/analytics/overview?range=7d              -> 200, series length 7
GET /api/v1/analytics/overview?range=30d             -> 200, series length 30
GET /api/v1/analytics/overview?range=custom&from=2026-08-01&to=2026-08-03 -> 200, series length 3
GET /api/v1/analytics/overview?range=custom&from=2026-01-01&to=2026-04-01 -> 400 INVALID_REQUEST
```

Check that `requestId` is present and that a UTC event near midnight is grouped under the expected Beijing date.

- [ ] **Step 3: Write the status document and update M4.1 handoff**

Record exact test output, smoke responses, the process-local receipt limitation, and the next M4.3 work. Keep M4.1’s delivered behavior unchanged and link its remaining-work section to the new status document.

- [ ] **Step 4: Perform final diff and scope review**

Run `rg -n "prompt|output|token|sessionId|deviceId" apps/api/src/main/java/com/huawei/skillcenter/analytics apps/web/src` and confirm no sensitive payload field is added to analytics. Review all modified files against the design and report any unimplemented non-goal rather than silently expanding scope.

## Plan self-review

- Spec coverage: Tasks 1–2 cover range parsing and ingestion quality; Task 3 covers all response metrics and Beijing aggregation; Task 4 covers API, actor scope and errors; Task 5 covers the complete dashboard interaction; Task 6 covers docs and acceptance evidence.
- Placeholder scan: no `TBD`, `TODO`, or unspecified implementation step is used; each task has concrete files, signatures, tests, and commands.
- Type consistency: `AnalyticsQuery` is produced by Task 1 and consumed by Tasks 3–4; `InvocationIngestionStats` is produced by Task 2 and consumed by Task 3; extended `AnalyticsOverview` is produced by Task 3 and consumed by Tasks 4–5; frontend helper exports are produced and tested in Task 5.
