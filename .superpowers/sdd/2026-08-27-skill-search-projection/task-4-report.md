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

## Lifecycle identity follow-up

### Changed paths

- `apps/api/src/main/java/com/huawei/skillcenter/search/GovernedSkillSearchDocumentSource.java`
  - Removes the fallback to an arbitrary record when the selected governed version has no exact-version record. Such a version now produces no search document.
- `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillCatalogService.java`
  - Requires every source-resolved indexed candidate to match the current latest governance version by skill ID, version, and normalized lifecycle status. A latest withdrawn version therefore suppresses any stale index hit.
- `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchDocumentSourceTest.java`
  - Adds the no-exact-version source regression.
- `apps/api/src/test/java/com/huawei/skillcenter/skill/SkillCatalogSearchIndexTest.java`
  - Adds fake-source regressions for stale version, withdrawn current version, and stale lifecycle status.

### TDD evidence

RED command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillCatalogSearchIndexTest,SkillSearchDocumentSourceTest" test
```

Result: 4 expected assertion failures: an arbitrary fallback record produced a document, and the indexed catalog returned source records with stale version, withdrawn governance status, and mismatched status.

GREEN command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillCatalogSearchIndexTest,SkillSearchDocumentSourceTest" test
```

Result: exit code 0.

Focused regression command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillCatalogSearchIndexTest,SkillControllerTest,SkillSearchContractTest,JsonSkillSearchIndexTest,SkillSearchDocumentSourceTest,SkillSearchRefreshCoordinatorTest" test
```

Result: exit code 0. `SkillController` continues to resolve the ordinary GET actor and call `service.list(query, actor)`.

API compile command:

```powershell
mvn.cmd -q -DskipTests compile
```

Result: exit code 0.

### Self-review

- The ordinary controller GET actor-aware route was inspected and left unchanged.
- The catalog guard chooses the same deterministic lifecycle ordering as the source, but considers the latest version regardless of status so a newer withdrawal suppresses stale published/deprecated candidates.
- The indexed path performs the identity check before authorization, result totals, ordering, and pagination; no source record is treated as trusted solely because an index hit exists.
