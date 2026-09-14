# 评测套件不可变版本与用例资产 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将评测套件从 `suiteId` 唯一资产升级为同一逻辑套件下可复用、可审计、不可覆盖的版本化质量资产，并让评测、Benchmark、版本对比和兼容性矩阵固定引用实际套件版本。

**Architecture:** 保留 `EvaluationSuite` 作为不可变版本记录，以 `suiteId + suiteVersion` 复合键存储；`enabled` 只表示逻辑套件当前激活指针。所有新评测先解析并固定套件版本，质量证据恢复校验使用同一复合引用；旧请求缺少版本时解析唯一启用版本。前端按版本展示和选择，并提供复制版本创建新资产，不提供原版本更新/删除。

**Tech Stack:** Spring Boot 3.4.5 / Java 21 / Jackson JSON 原子快照 / JUnit 5 + AssertJ + MockMvc / React 19 / Vite / Node test runner。

**Spec:** `docs/superpowers/specs/2026-08-24-evaluation-suite-versioning-design.md`

## Global Constraints

- 同一 `suiteId + suiteVersion` 不得覆盖；版本内容创建后不可修改。
- 同一逻辑 `suiteId` 最多一个 `enabled=true` 版本；创建启用新版本时原子切换旧指针。
- 新请求的 `suiteVersion` 可选；省略时解析启用版本，`EvaluationRun.suiteVersion` 必须保存实际版本。
- 套件 ID、版本、case ID、名称和数量必须有界；每个版本最多 100 个 case，case ID 在版本内唯一。
- 不保存 Prompt、输入输出、工具参数、凭据、文件正文或 Provider 异常正文。
- 质量快照、逐用例结果、矩阵和 Benchmark 只能引用存在且上下文一致的套件版本。
- 管理 API 只允许 `admin`；错误沿用 ApiResponse/requestId/error envelope 和稳定错误码。
- 所有兼容构造器、旧 JSON 快照和缺失请求字段必须继续可恢复；不执行一次性破坏性迁移。

---

### Task 1: Freeze suite-version domain validation and error contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualitySuiteVersionConflictException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualitySuiteVersionNotFoundException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualitySuiteNotEnabledException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/EvaluationCase.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/EvaluationSuite.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/EvaluationSuiteRequest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/EvaluationRequest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixCreateRequest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkRequest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/EvaluationSuiteDomainTest.java`

**Interfaces:**
- `EvaluationSuiteRequest` continues accepting `(id, name, version, enabled, cases)` and rejects invalid identifiers, blank names, empty cases, duplicate case IDs, more than 100 cases, and overlong fields.
- `EvaluationRequest` gains `String suiteVersion` as an optional record field and keeps existing 5-argument and 8-argument constructors delegating `suiteVersion=""`.
- `CompatibilityMatrixCreateRequest` gains optional `suiteVersion` and keeps its existing constructor overloads.
- `BenchmarkRequest` gains optional `suiteId` and `suiteVersion` and keeps existing constructor overloads.
- New exceptions carry the affected logical ID/version and stable messages; controllers/services use them without exposing storage details.

- [ ] **Step 1: Write failing domain and compatibility tests.** Add tests asserting valid `smoke-v1` requests normalize blank optional versions to `""`; invalid suite IDs/versions, duplicate case IDs, empty cases, 101 cases, and overlong fields throw `IllegalArgumentException`. Add tests constructing old request signatures and asserting they still have an empty optional version.

```java
@Test
void acceptsVersionedSuiteAndKeepsOldRequestConstructorsCompatible() {
    EvaluationSuiteRequest request = new EvaluationSuiteRequest(
            "smoke", "Smoke", "smoke-v2", true,
            List.of(new EvaluationCase("case-1", "稳定路径")));

    assertThat(request.version()).isEqualTo("smoke-v2");
    assertThat(new EvaluationRequest("skill", "1.0.0", "smoke", "success", 1000)
            .suiteVersion()).isEmpty();
}
```

- [ ] **Step 2: Run the focused test to verify it fails.**

Run: `mvn.cmd -q "-Dtest=EvaluationSuiteDomainTest" test`

Expected: FAIL because the new validation and `suiteVersion` accessors do not yet exist.

- [ ] **Step 3: Implement bounded immutable-domain validation.** Normalize null optional versions to empty strings, validate bounded identifiers and names, copy case lists defensively, and add the backward-compatible constructors. Do not add any prompt/input/output fields.

- [ ] **Step 4: Run the focused test to verify it passes.**

Run: `mvn.cmd -q "-Dtest=EvaluationSuiteDomainTest" test`

Expected: PASS with all domain and constructor compatibility assertions green.

---

### Task 2: Store suites by composite identity and validate restored references

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceStore.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/QualityEvaluationServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/QualityEvidencePersistenceTest.java`

**Interfaces:**
- Add package-visible/public `EvaluationSuite resolveSuite(String suiteId, String requestedVersion)` to `QualityEvaluationService`.
- `resolveSuite` returns the exact enabled version for a nonblank request or the sole enabled version for a blank request; it throws `QualitySuiteVersionNotFoundException` or `QualitySuiteNotEnabledException` as specified.
- `createSuite` performs duplicate checking and enabled-pointer switching inside one `evidenceRepository.update` transaction, then returns the newly created immutable version.
- `listSuites` returns every version sorted by logical ID ascending and version descending.

- [ ] **Step 1: Write failing service tests for two versions and pointer switching.** Create `smoke-v1`, create enabled `smoke-v2`, assert both are listed, only v2 is enabled, blank resolution returns v2, exact resolution returns v2, and duplicate v1 creation leaves v1 unchanged and throws the conflict exception.

```java
@Test
void createsMultipleImmutableVersionsAndResolvesTheEnabledPointer() {
    service.createSuite(suite("smoke", "smoke-v1", true, "case-1"));
    service.createSuite(suite("smoke", "smoke-v2", true, "case-2"));

    assertThat(service.resolveSuite("smoke", "").version()).isEqualTo("smoke-v2");
    assertThat(service.resolveSuite("smoke", "smoke-v1").cases())
            .extracting(EvaluationCase::id).containsExactly("case-1");
    assertThat(service.listSuites()).extracting(EvaluationSuite::version)
            .containsExactly("smoke-v2", "smoke-v1");
}
```

- [ ] **Step 2: Run the focused tests to verify the new behavior fails.**

Run: `mvn.cmd -q "-Dtest=QualityEvaluationServiceTest,QualityEvidencePersistenceTest" test`

Expected: FAIL because the current Map is keyed only by `suite.id()` and persistence rejects duplicate IDs.

- [ ] **Step 3: Implement composite-key catalog behavior.** Key the in-memory suite map with `suiteKey(id, version)`, update default-suite initialization and restoration, resolve exact/active versions, atomically switch enabled flags, and preserve old constructors/snapshots. Keep active evaluation validation fail-closed for disabled versions.

- [ ] **Step 4: Update persistence validation to use exact suite references.** Replace suite-ID uniqueness with composite-key uniqueness, enforce one enabled version per logical ID, validate run/snapshot/case-result suite ID and version together, and retain the existing historical empty-catalog compatibility branch.

- [ ] **Step 5: Run focused service and persistence tests.**

Run: `mvn.cmd -q "-Dtest=QualityEvaluationServiceTest,QualityEvidencePersistenceTest" test`

Expected: PASS, including old JSON recovery and duplicate/corrupt composite-reference rejection.

---

### Task 3: Fix evaluation and compatibility-matrix version pinning

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/CompatibilityMatrixService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/QualityEvaluationServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/CompatibilityMatrixServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/CompatibilityMatrixEvidencePersistenceTest.java`

**Interfaces:**
- `QualityEvaluationService.submit` resolves the suite once, uses its cases, and writes `EvaluationRun.suiteVersion` from the resolved suite.
- `CompatibilityMatrixService.create` resolves `request.suiteVersion`, writes it to `CompatibilityMatrixRun`, and sends it in every child `EvaluationRequest`.
- Matrix restart orchestration uses the persisted `run.suiteVersion`; it never falls back to the current enabled version.

- [ ] **Step 1: Add failing tests for explicit and pinned versions.** Create v1/v2, submit an evaluation explicitly against v1, then enable v2 and assert the run remains v1. Add a matrix test that creates against v1, switches the active pointer, and asserts every child run and matrix record remains v1 after completion/restart.

- [ ] **Step 2: Run focused tests to verify they fail.**

Run: `mvn.cmd -q "-Dtest=QualityEvaluationServiceTest,CompatibilityMatrixServiceTest,CompatibilityMatrixEvidencePersistenceTest" test`

Expected: FAIL because `EvaluationRequest` has no version pinning and matrix child requests currently resolve only by ID.

- [ ] **Step 3: Implement resolution before asynchronous execution.** Resolve once during submission, use the returned suite object for case iteration, and pass `suiteVersion` through matrix child requests. Keep the old no-version constructor path resolving the enabled version.

- [ ] **Step 4: Run focused tests to verify pinning.**

Run: `mvn.cmd -q "-Dtest=QualityEvaluationServiceTest,CompatibilityMatrixServiceTest,CompatibilityMatrixEvidencePersistenceTest" test`

Expected: PASS with exact version persisted across async completion and restart.

---

### Task 4: Make Benchmark and version comparison choose exact suite context

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityComparisonService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkRequest.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkResult.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/SkillQualityController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkController.java` to accept optional suite-context filters on benchmark history
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/QualityComparisonServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/BenchmarkServiceEnvironmentTest.java`

**Interfaces:**
- Add comparison overload accepting `(suiteId, suiteVersion)` while preserving existing overloads.
- Snapshot selection filters exact `suiteId + suiteVersion`; a blank pair resolves the current enabled suite through `QualityEvaluationService`.
- `BenchmarkResult` exposes `suiteId` and `suiteVersion`, with an old constructor that defaults both to empty for old JSON.
- Existing comparisons with no suite fields remain source-compatible and return the same result when only one suite version exists.

- [ ] **Step 1: Write failing tests for ambiguous snapshot selection.** Seed two completed snapshots for the same Skill/version under different suite versions; assert an explicit Benchmark/comparison request uses only the requested suite and an omitted request uses the enabled suite. Assert mismatched suite versions produce `NO_COMPARABLE_SNAPSHOT`/context mismatch rather than mixing evidence.

- [ ] **Step 2: Run focused tests to verify ambiguity exists.**

Run: `mvn.cmd -q "-Dtest=QualityComparisonServiceTest,BenchmarkServiceEnvironmentTest" test`

Expected: FAIL because the current `latest` query filters only Skill version and environment.

- [ ] **Step 3: Implement exact suite-context selection and result propagation.** Validate/normalize optional suite fields, resolve the active version when omitted, filter both snapshots and comparison inputs, and persist the actual suite context in BenchmarkResult without storing business content.

- [ ] **Step 4: Run focused comparison and Benchmark tests.**

Run: `mvn.cmd -q "-Dtest=QualityComparisonServiceTest,BenchmarkServiceEnvironmentTest" test`

Expected: PASS with old constructor compatibility and deterministic suite selection.

---

### Task 5: Expose stable API errors and suite-version request fields

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvaluationController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/BenchmarkController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/SkillQualityController.java`
- Modify: `apps/web/src/api/skillApi.js`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/QualityEvaluationControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/CompatibilityMatrixControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/api/ApiErrorContractTest.java`
- Test: `apps/web/tests/api-client.test.mjs`

**Interfaces:**
- Map the three suite exceptions to deterministic 409/404/409 envelopes with codes `QUALITY_SUITE_VERSION_CONFLICT`, `QUALITY_SUITE_VERSION_NOT_FOUND`, and `QUALITY_SUITE_NOT_ENABLED`.
- Keep `/admin/quality/suites` response shape compatible; no update/delete route is added.
- API client methods continue to accept generic input objects and pass `suiteVersion`, `suiteId`, and comparison query parameters through unchanged.

- [ ] **Step 1: Add failing MockMvc/client tests.** Verify duplicate version, exact missing version, and no-enabled-version responses expose stable codes and request IDs; verify client JSON includes new optional fields without breaking old input.

- [ ] **Step 2: Run focused controller/client tests to verify failure.**

Run: `mvn.cmd -q "-Dtest=QualityEvaluationControllerTest,CompatibilityMatrixControllerTest,ApiErrorContractTest" test` and `npm.cmd test -- --test-name-pattern="suite|benchmark|quality"` from `apps/web`.

Expected: FAIL or generic `INVALID_REQUEST` because no suite-specific exception mappings/client assertions exist.

- [ ] **Step 3: Implement mappings and query/body propagation.** Reuse the existing error-envelope helpers, require admin as before, and ensure no exception message includes persisted case names or paths.

- [ ] **Step 4: Run focused API and Web contract tests.**

Expected: PASS with stable status/code/requestId assertions.

---

### Task 6: Add quality-center version selection and copy-as-new-version flow

**Files:**
- Modify: `apps/web/src/QualityCenterView.jsx`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/src/api/skillApi.js` to expose suite query parameters through the existing generic query helper
- Modify: `apps/web/src/quality.js` to normalize optional suite-version fields from legacy payloads
- Modify: `apps/web/src/styles.css` for the version/case form and copy action in the existing suite layout
- Test: `apps/web/tests/quality-center-view.test.mjs`
- Test: `apps/web/tests/detail-view-interaction.test.mjs`
- Test: `apps/web/tests/operations-metrics.test.mjs`

**Interfaces:**
- Suite selection stores both `suiteId` and `suiteVersion`; evaluation, matrix and Benchmark requests use the selected version. The detail comparison panel passes its explicit suite context to the comparison API and renders the returned suite version.
- Suite creation form stores a user-entered version and bounded case list; its default remains `${id}-v1` with the existing default case.
- “复制为新版本” copies the selected suite’s ID/name/cases into the form and proposes the next deterministic version label; the user confirms before POST.

- [ ] **Step 1: Write failing React interaction tests.** Assert multiple versions render separately, selecting a version causes evaluation/matrix/Benchmark request bodies to include `suiteVersion`, and copying a version pre-fills the form cases/version without mutating the source row.

```js
assert.match(document.querySelector("[data-testid=quality-suites]")?.textContent || "", /smoke-v2/);
await act(async () => document.querySelector("[data-testid=suite-copy]").click());
assert.equal(document.querySelector("[aria-label='新套件版本']")?.value, "smoke-v3");
```

- [ ] **Step 2: Run the focused Web tests to verify they fail.**

Run: `npm.cmd test -- --test-name-pattern="suite|benchmark|compatibility matrix"` from `apps/web`.

Expected: FAIL because the current UI hardcodes `id-v1`, displays no copy action, and sends no `suiteVersion`.

- [ ] **Step 3: Implement version-aware state and safe copy flow.** Normalize missing suite versions from old mocks, keep active selection stable after reload, add the version/case fields and copy button, disable submit while creating, and keep 业务正文 out of the rendered/persisted request.

- [ ] **Step 4: Run the focused Web tests.**

Expected: PASS with multi-version rendering, copy behavior, request propagation, empty state, and API failure feedback.

---

### Task 7: Documentation, regression verification, and lifecycle evidence

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/superpowers/specs/2026-08-12-internal-skill-center-design.md` with the versioned-suite lifecycle boundary
- Test/verification: existing full Web/API suites and lifecycle script

- [ ] **Step 1: Add the completed capability and explicit external boundary.** Document suite version persistence, copy/reuse, exact evaluation/matrix/Benchmark references, old-client compatibility, and the fact that real Provider integration remains external.

- [ ] **Step 2: Run focused backend and frontend regressions.**

Run:

```powershell
mvn.cmd -q "-Dtest=EvaluationSuiteDomainTest,QualityEvaluationServiceTest,QualityEvidencePersistenceTest,CompatibilityMatrixServiceTest,QualityComparisonServiceTest,BenchmarkServiceEnvironmentTest,QualityEvaluationControllerTest,CompatibilityMatrixControllerTest" test
cd ..\web
npm.cmd test -- --test-name-pattern="suite|benchmark|compatibility matrix|quality center"
```

- [ ] **Step 3: Run full verification before claiming completion.**

Run from `apps/web`: `npm.cmd test` and `npm.cmd run build`.

Run from `apps/api`: `mvn.cmd -q test`.

Run from repository root: `pwsh -NoProfile -File scripts/verify-lifecycle.ps1 -SkipBuild -SkipSmoke` and `git diff --check`.

Expected: all tests pass, Web build produces Sites artifacts, API Surefire reports contain zero failures/errors, lifecycle verification completes, and diff check has no whitespace errors.
