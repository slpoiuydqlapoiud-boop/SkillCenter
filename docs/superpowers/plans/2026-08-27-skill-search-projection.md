# A4.1 Skill Search Projection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将市场 Skill 查询从请求时全量子串扫描升级为可重建、权限感知、可观测且可替换的搜索投影。

**Architecture:** 从治理后的目录事实抽取不含敏感正文的 `SkillSearchDocument`，由 `SkillSearchIndex` 端口负责规范化、倒排候选、相关度和状态。默认实现为内存 JSON 模式；`SkillSearchRefreshCoordinator` 通过内部刷新事件和管理员 rebuild 维护索引，`SkillCatalogService` 只消费候选并执行现有授权服务二次校验。事实源仍是 Governance/Scope/Release/Artifact 域，索引不是主数据。

**Tech Stack:** Java 21、Spring Boot 3.4、JUnit 5、AssertJ、Mockito、现有 `ActorResolver`/`SkillAuthorizationService`/`ApiResponse`、React 19/Vite（仅兼容性回归）。

**Spec:** `docs/superpowers/specs/2026-08-27-skill-search-projection-design.md`

## Global Constraints

- 默认不引入 OpenSearch、分词服务或第三方 SDK；未安装外部搜索服务时 JSON 模式必须可启动。
- 索引不得保存 Prompt、`SKILL.md` 正文、输入输出、工具参数、制品路径、凭据、Token、Trace 或原始异常。
- 搜索结果必须经过 `SkillAuthorizationService.requireVisible(skillId, actor, SkillVisibilityContext.CATALOG)` 二次校验。
- `published`/`deprecated` 可进入目录索引，`withdrawn` 不得进入；旧 API 保持 `items/page/pageSize/total` 兼容。
- 重建必须是新快照成功后一次替换；失败保留上一版可读索引并返回稳定错误码。
- source hash 相同的重建幂等且不增加 revision；状态接口不向普通用户暴露完整 hash。
- 所有新增行为先写 RED 测试并确认因功能缺失失败，再写最小生产实现。

---

### Task 1: Freeze search domain contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchDocument.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchQuery.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchHit.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexStatus.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRebuildResult.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchDocumentSnapshot.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndex.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchContractTest.java`

**Interfaces:**
- Produces `SkillSearchIndex.status()`, `rebuild(List<SkillSearchDocument>, String)`, `invalidate(String)`, and `search(SkillSearchQuery)`.
- `SkillSearchDocument` exposes only bounded metadata: `skillId`, `name`, `description`, `tags`, `team`, `category`, `status`, `risk`, `lastUpdated`, `publishedAt`, `latestVersion`, `visibility`, `ownerTeamId`.
- `SkillSearchQuery` carries normalized `text`, `category`, `status`, `risk`, and `sort`; it has no pagination fields because authorization must run before pagination.
- `SkillSearchHit` carries `skillId`, deterministic `score`, and allow-listed `matchedFields`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void searchContractsRejectUnboundedOrSensitiveDocumentFields() {
    assertThatThrownBy(() -> new SkillSearchDocument(
            "skill-a", "Name", "description", List.of("tag"), "team", "other",
            "published", "low", Instant.now(), Instant.now(), "1.0.0", "PUBLIC", "team"))
            .doesNotThrowAnyException();
    assertThat(SkillSearchQuery.of("  EOX   查询  ", "", "", "", "updated").text())
            .isEqualTo("EOX 查询");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchContractTest" test` from `apps/api`.

Expected: test compilation fails because the search contracts do not exist.

- [ ] **Step 3: Write minimal implementation**

Implement immutable records with bounded identifier/text normalization, fixed status vocabulary, immutable lists, and the exact port methods above. Define `SkillSearchDocumentSnapshot(List<SkillSearchDocument> documents, String sourceHash, long sourceRevision)` and require its document list to be immutable. `SkillSearchIndex` must not expose a method accepting raw request bodies, SQL, index mappings, or arbitrary metadata.

- [ ] **Step 4: Run the test to verify it passes**

Run the same Maven command; expected result is PASS with no failure or error.

### Task 2: Implement deterministic JSON search index

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/JsonSkillSearchIndex.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/search/JsonSkillSearchIndexTest.java`

**Interfaces:**
- Consumes the contracts from Task 1.
- Produces normalized token lookup, weighted scoring, matched fields, stable sorting and atomic rebuild behavior.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void searchesSkillIdNameTagsDescriptionTeamAndCategoryWithStableWeights() {
    JsonSkillSearchIndex index = new JsonSkillSearchIndex();
    index.rebuild(List.of(document("eox-query", "EOX 查询", "release helper",
            List.of("release"), "platform", "efficiency")), "hash-1");

    List<SkillSearchHit> hits = index.search(SkillSearchQuery.of("EOX", "", "", "", "relevance"));

    assertThat(hits).extracting(SkillSearchHit::skillId).containsExactly("eox-query");
    assertThat(hits.getFirst().matchedFields()).contains("id", "name");
}

@Test
void failedRebuildKeepsTheLastCommittedIndexAndSameHashIsIdempotent() {
    JsonSkillSearchIndex index = new JsonSkillSearchIndex();
    index.rebuild(List.of(document("skill-a", "A", "safe", List.of(), "team", "other")), "hash-1");
    int revision = index.status().revision();

    assertThatThrownBy(() -> index.rebuild(List.of((SkillSearchDocument) null), "hash-2"))
            .isInstanceOf(IllegalArgumentException.class);
    assertThat(index.search(SkillSearchQuery.of("skill-a", "", "", "", "relevance")))
            .hasSize(1);
    assertThat(index.rebuild(List.of(document("skill-a", "A", "safe", List.of(), "team", "other")), "hash-1")
            .revision()).isEqualTo(revision);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=JsonSkillSearchIndexTest" test` from `apps/api`.

Expected: compilation or assertion failure because no index implementation exists.

- [ ] **Step 3: Write minimal implementation**

Normalize Unicode case and whitespace; create tokens for complete identifiers and bounded prefixes; require every query token to match at least one field. Use fixed weights `id=100`, `name=80`, `tags=60`, `description=40`, `team/category=20`. Sort by score descending, status priority, `lastUpdated` descending, and `skillId` ascending. Return at most 5000 candidates and apply category/status/risk filters before scoring. Replace the immutable document map only after validating every document. `invalidate` changes status to `STALE` without deleting the last committed snapshot.

- [ ] **Step 4: Run tests to verify they pass**

Run the focused index tests and the contract test. Expected: all pass; repeated rebuild with the same hash leaves revision unchanged.

### Task 3: Extract document source and refresh coordinator

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchDocumentSource.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRefreshEvent.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRefreshCoordinator.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchRefreshCoordinatorTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchDocumentSourceTest.java`

**Interfaces:**
- `SkillSearchDocumentSource.snapshot()` returns a deterministic `SkillSearchDocumentSnapshot(documents, sourceHash, sourceRevision)`.
- `SkillSearchDocumentSource.findRecord(String skillId)` reads one governed record without scanning the full catalog.
- `SkillSearchRefreshCoordinator.invalidate(SkillSearchRefreshEvent)` calls `index.invalidate(reasonCode)`.
- `SkillSearchRefreshCoordinator.ensureReady()` performs at most one initial rebuild when the index has no committed snapshot.
- `SkillSearchRefreshCoordinator.rebuild(String expectedSourceHash, String actor, String requestId)` builds from the source, checks the optional expected hash, and delegates atomic rebuild.
- `SkillSearchRefreshCoordinator.status()` returns the index status without exposing sensitive source data.

- [ ] **Step 1: Write the failing tests**

Test that withdrawn records are excluded, only allow-listed metadata enters documents, same source facts in different list orders produce the same hash, invalid expected hash returns `SEARCH_INDEX_SOURCE_CONFLICT`, and an event makes the index `STALE` without deleting committed hits.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchDocumentSourceTest,SkillSearchRefreshCoordinatorTest" test`.

Expected: compilation failure for the missing source/coordinator types.

- [ ] **Step 3: Write minimal implementation**

Extract from the existing governed catalog facts without reading Markdown or ZIP content. Canonicalize by Skill ID and the fixed field order, hash UTF-8 canonical text with SHA-256, and use `ApplicationEventPublisher` for internal refresh events. A rebuild failure must retain the previous index and emit only a stable result code; audit metadata may contain actor/requestId, counts, revision and hash digest, never source documents.

- [ ] **Step 4: Run tests to verify they pass**

Run both focused test classes; expected PASS and no sensitive field in any document or status string.

### Task 4: Replace catalog request-time scanning with index candidates

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillCatalogService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillSummary.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/skill/SkillCatalogSearchIndexTest.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/skill/SkillControllerTest.java` only for compatible search metadata assertions

**Interfaces:**
- `SkillCatalogService` consumes `SkillSearchIndex`, `SkillSearchDocumentSource`, and `SkillAuthorizationService` through constructor injection.
- Normal list flow asks the index for candidates, maps candidates back to governed records, filters each candidate with `requireVisible`, then applies the existing response envelope and pagination.
- `SkillSummary` gets an optional `SkillSearchMetadata(score, matchedFields)` field with a backwards-compatible constructor; Jackson omits it when empty.

- [ ] **Step 1: Write the failing tests**

Test that an ID-only query returns the matching Skill, a restricted Skill is absent from both items and total, a withdrawn version is absent, `matchedFields` is present only for a text query, and the repository is not called for every page after the index is ready.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillCatalogSearchIndexTest,SkillControllerTest" test`.

Expected: the ID query misses records or compilation fails because the catalog is not wired to `SkillSearchIndex`.

- [ ] **Step 3: Write minimal implementation**

Move governed record materialization into `SkillSearchDocumentSource` so the catalog and coordinator share one source. Preserve legacy constructor overloads by supplying a local index/source only where tests or local-only callers do not provide them. Never use an index hit as proof of visibility; call authorization for every candidate before computing total and returning results. Keep existing category/risk/status validation and sort aliases.

- [ ] **Step 4: Run tests to verify they pass**

Run the focused catalog/controller tests and verify old response fields remain unchanged when `search` is empty.

### Task 5: Wire lifecycle refresh events and admin control API

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/VersionLifecycleService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillAuthorizationService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexController.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexAdminView.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchIndexControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchRefreshEventWiringTest.java`

**Interfaces:**
- Publish `SkillSearchRefreshEvent(skillId, sourceRevision, reasonCode)` after successful publish, deprecate, withdraw and scope save; failed domain mutations publish nothing.
- `GET /api/v1/admin/search/index/status` returns bounded backend/status/revision/documentCount/indexedAt/reasonCode.
- `POST /api/v1/admin/search/index/rebuild` accepts only `{ "expectedSourceHash": "..." }`, requires admin and requestId, and returns a stable rebuild result.

- [ ] **Step 1: Write the failing tests**

Test admin/non-admin authorization, missing request ID, expected-hash conflict, successful rebuild, stable status fields, event publication only after successful domain persistence, and no event payload containing Markdown/content/credentials.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchIndexControllerTest,SkillSearchRefreshEventWiringTest" test`.

Expected: endpoint and event listener are missing.

- [ ] **Step 3: Write minimal implementation**

Use `ActorResolver`/`RoleGuard` for admin-only control; use `RequestIdFilter` for request IDs; map source conflicts and persistence failures to existing global API error handling with stable `SEARCH_INDEX_SOURCE_CONFLICT` / `SEARCH_INDEX_REBUILD_FAILED` codes. Keep response bounded and never return exception text.

- [ ] **Step 4: Run tests to verify they pass**

Run the focused controller/event tests and verify successful lifecycle writes mark the index stale exactly once per event.

### Task 6: Configuration, observability, documentation and regression

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/superpowers/specs/2026-08-27-skill-search-projection-design.md` to mark implementation status after verification
- Test: existing `scripts/verify-lifecycle.ps1` flow and full API/Web suites

**Interfaces:**
- The implementation is always the default in-process JSON search projection in this phase; no selector or external dependency is added.
- The admin status endpoint reports `READY`/`STALE`/`DEGRADED` with stable reason codes and never blocks local development.

- [ ] **Step 1: Write the failing regression assertions**

Add a context test proving the application starts without OpenSearch and a status test proving index health is safe and bounded.

- [ ] **Step 2: Run focused regression**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearch*" test`.

Expected: all search tests pass after implementation.

- [ ] **Step 3: Run complete verification**

Run from `apps/api`: `mvn.cmd -q -DforkCount=0 test`; aggregate fresh `target/surefire-reports/TEST-*.xml` and record tests/failures/errors/skips. Run from `apps/web`: `npm test` and `npm run build` using the existing project scripts. Run `powershell -ExecutionPolicy Bypass -File scripts/verify-lifecycle.ps1` where the environment supports it. Run `git diff --check` and distinguish line-ending warnings from whitespace errors.

- [ ] **Step 4: Update evidence**

Record the actual test counts, Docker capability skips, default configuration, API paths, and remaining external-search/multi-instance limitations in `docs/project/remaining-coding-tasks-status.md`. Do not claim OpenSearch, PostgreSQL search, or multi-instance event delivery until separately installed and tested.

---

## Review Checklist

- [ ] Every spec section has a corresponding task above.
- [ ] No normal query path rebuilds or scans the full catalog.
- [ ] Authorization occurs after candidate lookup and before total/result exposure.
- [ ] Same source hash is idempotent; failed rebuild preserves the previous committed index.
- [ ] Withdrawn resources and sensitive content never enter the index or response.
- [ ] Existing API/Web tests and lifecycle verifier pass with fresh output.
