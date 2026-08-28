# Task 4 Report: Indexed Catalog Candidates

## Changed paths

- `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillCatalogService.java`
  - Adds the injected indexed actor-aware list path, while retaining the existing constructor-compatible scan path.
  - Calls the refresh coordinator, searches bounded candidates, resolves each candidate only through the source, authorizes before totals/pagination, preserves index order for `relevance`, and applies the existing sort comparator otherwise.
- `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillSummary.java`
  - Adds nullable `search` metadata without changing the source-compatible 15-argument constructor.
- `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillSearchMetadata.java`
  - Holds the public score and allow-listed hit fields exposed only for text search hits.
- `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillSort.java`
  - Preserves the `relevance` sort alias so the indexed path can retain search order.
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchCatalogConfiguration.java`
  - Supplies Spring beans for the in-process index, governed source, and refresh coordinator. This small additional wiring file avoids a circular dependency caused by declaring these beans on `SkillCatalogService`.
- `apps/api/src/test/java/com/huawei/skillcenter/skill/SkillCatalogSearchIndexTest.java`
  - Covers ID search, hidden and withdrawn omissions, metadata gating, bounded source reads/no repository page reads, post-authorization pagination, and relevance ordering.

## TDD evidence

### RED

Command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillCatalogSearchIndexTest,SkillControllerTest" test
```

Result: failed at test compilation exactly because `SkillSearchMetadata`, `SkillSummary.search()`, and the injected eight-argument `SkillCatalogService` constructor did not yet exist.

### GREEN

Command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillCatalogSearchIndexTest,SkillControllerTest" test
```

Result: exit code 0. `SkillCatalogSearchIndexTest` ran 5 tests and `SkillControllerTest` ran 4 tests with 0 failures/errors.

Focused Task 1–4 command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillCatalogSearchIndexTest,SkillControllerTest,SkillSearchContractTest,JsonSkillSearchIndexTest,SkillSearchDocumentSourceTest,SkillSearchRefreshCoordinatorTest" test
```

Result: exit code 0. 27 tests ran with 0 failures/errors.

The Maven runs emit pre-existing Mockito/JDK dynamic-agent warnings; no test failure or error was reported.

## Constructor and wiring decisions

- The autowired Spring constructor accepts `SkillSearchIndex`, `SkillSearchDocumentSource`, and `SkillSearchRefreshCoordinator` directly.
- Existing one-, three-, four-, and five-argument constructors remain source-compatible and deliberately leave indexed dependencies absent, retaining the legacy scan adapter only for those paths.
- The separate package-local configuration registers the in-process index, governed source, and coordinator. Moving these beans out of the service removes the Spring construction cycle observed in the first GREEN attempt.

## Security checks

- Candidate hits are not treated as visibility proof: every source-resolved catalog record calls `requireVisible(..., CATALOG)`.
- Only `SkillNotVisibleException` is omitted; unexpected authorization failures continue through existing error handling without exposing message text from this path.
- Missing, withdrawn, and non-catalog source records are omitted before item/total calculation.
- The indexed branch neither scans `governedRecords()` nor reads ZIP/Markdown content. Once ready, it performs only `findRecord(skillId)` reads for bounded index hits.
- Metadata is attached only to non-blank text queries and consists solely of score plus the index contract's allow-listed matched fields. `search` is Jackson-omitted when null.

## Self-review

- Checked constructor compatibility and confirmed existing controller tests remain green.
- Checked that visibility precedes sort, total, and pagination; hidden candidates neither affect totals nor appear in pages.
- Checked `relevance` preserves hit order and all other existing aliases use `SkillSort`.
- Ran `git diff --check`; no whitespace errors.
- Kept lifecycle, authorization mutation/event, admin, web, and persistence code untouched.

## Concerns

- Maven reports Mockito/JDK dynamic-agent deprecation warnings from the existing test setup; they are unrelated to Task 4.
- Lifecycle refresh event wiring remains intentionally deferred to Task 5. The coordinator's `ensureReady()` provides initial readiness and is a no-op after the index is ready.
