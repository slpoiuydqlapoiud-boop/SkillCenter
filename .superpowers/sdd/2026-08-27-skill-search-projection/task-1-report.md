# Task 1 implementation report

## Changed paths

- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchDocument.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchQuery.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchHit.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexStatus.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRebuildResult.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchDocumentSnapshot.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndex.java`
- `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchContractTest.java`

## TDD evidence

RED command, run from `apps/api`:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchContractTest" test
```

Expected failure: test compilation failed because `SkillSearchDocument` and `SkillSearchQuery` did not exist. The exit code was 1.

GREEN command, run from `apps/api`:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchContractTest" test
```

Actual summary: exit code 0; 1 test run, 0 failures, 0 errors, 0 skipped.

## Self-review findings

- All requested search contracts are new Java 21 records/interfaces in the search package.
- Document fields are limited to the required bounded metadata, with immutable tags and `Instant` date fields.
- Query text is whitespace-normalized and has no pagination fields.
- Hit matched fields are allow-listed and immutable.
- Snapshot documents are copied immutably; index operations contain no request-body, SQL, mapping, or arbitrary metadata inputs.
- No existing production or test files were modified.

## Concerns

The brief's mandated assertion uses `assertThatThrownBy(...).doesNotThrowAnyException()`, which is internally contradictory and cannot pass. The test preserves every required value and behavior but uses AssertJ's corresponding `assertThatCode(...).doesNotThrowAnyException()` so the required GREEN run is achievable.

## Round 1 fix report

### Changed paths

- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchQuery.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchDocument.java`
- `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchIndexStatus.java`
- `apps/api/src/test/java/com/huawei/skillcenter/search/SkillSearchContractTest.java`
- `.superpowers/sdd/2026-08-27-skill-search-projection/task-1-report.md`

### TDD evidence

After adding focused validation and immutability tests, RED command from `apps/api`:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchContractTest" test
```

Actual RED output: 4 tests run, 2 failures, 0 errors, 0 skipped. The failures were the invalid query filter and unknown index-state assertions, confirming the missing behavior.

GREEN command from `apps/api`:

```powershell
mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearchContractTest" test
```

Actual GREEN output: quiet Maven output, exit code 0; 4 tests run, 0 failures, 0 errors, 0 skipped.

### Self-review

- Non-empty query status and risk filters now reuse the fixed vocabularies and invalid values fail fast; empty filters remain valid.
- Index status now accepts only `READY`, `STALE`, `REBUILDING`, `DEGRADED`, or `NOT_READY`.
- Focused tests cover bounded document input, fixed vocabularies, immutable tags/hits/snapshots, allow-listed hit fields, and rebuild/status-result validation.
- No dependencies, API integrations, or unrelated files were changed.

### Concerns

The original brief assertion remains corrected from `assertThatThrownBy` to `assertThatCode` because the former cannot satisfy `doesNotThrowAnyException()`.
