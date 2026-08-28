# Task 3 report — document source and refresh coordinator

## Changed paths

- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchDocumentSource.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/GovernedSkillSearchDocumentSource.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRefreshEvent.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRefreshCoordinator.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexStatus.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRebuildResult.java`
- `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchDocumentSourceTest.java`
- `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchRefreshCoordinatorTest.java`

## TDD evidence

RED command, run from `apps/api`:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchDocumentSourceTest,SkillSearchRefreshCoordinatorTest" test
```

The clean RED run failed during test compilation only because `SkillSearchDocumentSource`, `GovernedSkillSearchDocumentSource`, `SkillSearchRefreshEvent`, and `SkillSearchRefreshCoordinator` did not exist, and because `SkillSearchRebuildResult.reasonCode()` was absent. A first RED attempt also identified one `SkillMetrics` fixture constructor mismatch; that test-only fixture was corrected before the clean RED confirmation.

GREEN commands:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchDocumentSourceTest,SkillSearchRefreshCoordinatorTest" test
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchContractTest,JsonSkillSearchIndexTest,SkillSearchDocumentSourceTest,SkillSearchRefreshCoordinatorTest" test
```

Both completed with exit code 0 and no Maven error/warning output. The final focused suite ran 16 tests across Task 1, Task 2, and Task 3 with zero failures or errors.

## Source and coordinator decisions

- The governed source uses only `GovernanceStore` version facts, `SkillRepository` metadata records, and `SkillScopeRepository` ownership/visibility facts. It never reads package contents, artifact paths, prompts, inputs/outputs, tokens, credentials, traces, or exception text.
- Only `published` and `deprecated` governed versions are selected. Selection is deterministic: the latest uploaded/published timestamp wins, followed by version and package ID. Output is ordered by `skillId`; tags are normalized, deduplicated, and sorted.
- The SHA-256 source hash uses UTF-8, explicit fixed field labels and boundaries, normalized Unicode/whitespace values, and sorted tag values. Input list ordering therefore cannot alter the selected document or hash.
- Scopes provide visibility and owner team. A legacy record without a scope uses `PUBLIC` visibility and an empty owner-team value; scope data is projection metadata only, not an authorization decision.
- `findRecord` returns the immutable source cache after a snapshot. A cache miss checks governed eligibility and performs exactly one repository detail lookup rather than a catalog page scan.
- The coordinator is injected with `SkillSearchIndex` and `SkillSearchDocumentSource`. It invalidates through the index, performs only an initial rebuild when no committed source hash exists, delegates the full snapshot to atomic rebuild, and retains the old index when rebuilding fails.
- `SkillSearchIndexStatus` and `SkillSearchRebuildResult` gained bounded `reasonCode` metadata with compatibility constructors retaining their prior four/five-argument call sites. `GovernanceStore.revision()` is the minimal read-only accessor used as the source revision.

## Self-review

- Confirmed source document fields exactly match the Task 1 allow-list.
- Confirmed events have exactly `skillId`, non-negative `sourceRevision`, and bounded `reasonCode` components.
- Confirmed conflict handling returns `SEARCH_INDEX_SOURCE_CONFLICT` before `index.rebuild` and invalidation leaves existing hits searchable.
- Confirmed no controller, catalog, lifecycle, web, dependency, database, OpenSearch, tokenizer, provider SDK, or message-platform changes were introduced.
- Ran `git diff --check`; no whitespace errors were reported for Task 3 changes.

## Concerns

The workspace contains extensive unrelated user changes. The amended Task 3 commit excludes `GovernanceStore` and every A3 access file.

## Fix round 1 — dependency-safe Task 3 commit

Review identified two dependencies that prevented the original Task 3 commit from compiling on `499d9d8`: the source imported the uncommitted A3 `SkillScopeRepository`, and its `GovernanceStore.revision()` accessor referenced an uncommitted field.

RED command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchDocumentSourceTest" test
```

The test compilation failed as expected because `SkillSearchScope`, `SkillSearchScopeProvider`, and the new source constructor overloads did not exist. The existing source constructor still required the A3 `SkillScopeRepository`.

Fix:

- Added search-owned `SkillSearchScope`, `SkillSearchScopeProvider`, and `SkillSearchSourceRevisionProvider` boundaries.
- Added source constructors with safe defaults: no scope produces `PUBLIC` and an empty owner team; no revision provider produces `0`.
- Kept a four-argument integration constructor so Task 4 can adapt actual scope and governance-revision sources without creating an A3 compile dependency.
- Removed `GovernanceStore.revision()` from the amended Task 3 commit.

GREEN command:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchDocumentSourceTest,SkillSearchRefreshCoordinatorTest" test
```

It completed with exit code 0 and no Maven error/warning output. Final verification also runs the Task 1–3 focused suite and the amended commit in a clean checkout rooted at `499d9d8`.

Clean-checkout verification then identified a final baseline mismatch: `SkillVersion.riskLevel()` is not part of the `499d9d8` record. A new repository-risk metadata test produced RED (`expected high but was low`), then the source was changed to use the allow-listed `SkillRecord.risk()` metadata. Both the focused source/coordinator suite and the final Task 1–3 suite completed with exit code 0 afterward.
