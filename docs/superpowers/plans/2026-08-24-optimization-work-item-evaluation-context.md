# 优化工作项评测上下文绑定实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or superpowers:subagent-driven-development) to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** 为优化工作项建立不可变的 suiteId + suiteVersion 评测上下文，使新工作项的 Evaluation Run、Quality Snapshot 和 Benchmark 证据不能跨评测套件版本串线，同时兼容旧工作项。

**Architecture:** 在 OptimizationWorkItem 和创建请求中增加成对的套件元数据，并保留旧 Java 构造函数和空字段兼容路径。服务层把套件上下文纳入三类证据匹配；旧工作项使用既有套件通配语义，但证据自身的套件字段必须成对。Quality Center 使用当前已加载并选中的套件版本创建工作项，并展示锁定状态。

**Tech Stack:** Java 17/Spring Boot/Jackson/JUnit 5/AssertJ/Mockito；React/Vite/Node test；现有 JSON 原子持久化与 Quality Center API 封装。

**Spec:** docs/superpowers/specs/2026-08-24-optimization-work-item-evaluation-context-design.md

## Global Constraints

- 套件上下文只允许 suiteId、suiteVersion 两个有界标识符，长度不超过 128，匹配 [A-Za-z0-9][A-Za-z0-9._:-]{0,127}。
- 两个字段必须同时为空或同时非空；单字段上下文必须被拒绝。
- 旧工作项缺失两个字段时反序列化为空，并保留原有证据匹配行为；不得隐式回填历史套件。
- 状态流转和证据绑定不得修改已锁定的套件上下文。
- 证据匹配必须同时保留 Skill、候选版本、数据源、Runtime/MCP/LLM 条件。
- 证据上下文不匹配继续使用 HTTP 422 和 OPTIMIZATION_WORK_ITEM_EVIDENCE_INVALID。
- 不持久化 Prompt、评测输入输出、凭据或业务文本；审计只写安全元数据。
- 不改变活动工作项的既有业务去重键，不新增端点，不触碰无关的既有工作区改动，不创建提交。

---

### Task 1: 扩展工作项领域模型并锁定持久化兼容边界

**Files:**
- Modify: apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItem.java
- Modify: apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemCreateRequest.java
- Modify: apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemStoreTest.java
- Create: apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemDomainTest.java

**Interfaces:**
- Produces a 25-component OptimizationWorkItem canonical record with suiteId and suiteVersion immediately after llmProviderId.
- Preserves the existing 23-argument OptimizationWorkItem constructor, defaulting both suite fields to empty strings.
- Produces an 11-component OptimizationWorkItemCreateRequest record with the two suite fields after llmProviderId, plus the existing 9-argument constructor defaulting both to empty strings.

- [ ] **Step 1: Write failing domain and compatibility tests**

Add tests that make the intended constructors and invariants executable:

~~~java
@Test
void acceptsPinnedSuiteContextAndNormalizesIdentifiers() {
    OptimizationWorkItem item = workItem(" suite-a ", " suite-v1 ");

    assertThat(item.suiteId()).isEqualTo("suite-a");
    assertThat(item.suiteVersion()).isEqualTo("suite-v1");
}

@Test
void rejectsPartialOrUnboundedSuiteContext() {
    assertThatThrownBy(() -> workItem("suite-a", ""))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> workItem("bad value", "suite-v1"))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void legacyConstructorAndLegacyJsonRemainReadable() throws Exception {
    OptimizationWorkItem legacy = existing23ArgumentWorkItem();
    assertThat(legacy.suiteId()).isEmpty();
    assertThat(legacy.suiteVersion()).isEmpty();

    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    String json = mapper.writeValueAsString(legacy)
            .replace(",\"suiteId\":\"\",\"suiteVersion\":\"\"", "");
    OptimizationWorkItem reloaded = mapper.readValue(json, OptimizationWorkItem.class);
    assertThat(reloaded.suiteId()).isEmpty();
    assertThat(reloaded.suiteVersion()).isEmpty();
}
~~~

Use the existing test helper values from OptimizationWorkItemStoreTest; keep all test data as bounded IDs and non-sensitive metadata.

- [ ] **Step 2: Run the focused tests and confirm the failure**

Run from apps/api:

~~~powershell
mvn.cmd -q -Dtest=OptimizationWorkItemDomainTest,OptimizationWorkItemStoreTest test
~~~

Expected: compilation or assertion failures because the two record components, overloads, and pair validation do not yet exist.

- [ ] **Step 3: Implement the model and request compatibility layer**

In OptimizationWorkItem:

1. Add suiteId and suiteVersion after llmProviderId.
2. Normalize both through the existing optional identifier path.
3. Reject exactly one nonblank field with IllegalArgumentException.
4. Validate each nonblank value with the existing bounded identifier regex.
5. Add the old 23-argument constructor and delegate with two empty strings.

In OptimizationWorkItemCreateRequest:

1. Add the two record components after llmProviderId.
2. Add the old 9-argument constructor delegating with two empty strings.
3. Do not reject both-empty requests; this preserves the API compatibility path.

- [ ] **Step 4: Add persisted partial-context rejection**

Extend OptimizationWorkItemStoreTest with a JSON fixture containing one nonblank suite field and assert that constructing OptimizationWorkItemStore throws IllegalStateException with Unable to read optimization work items. The record constructor must be the validation point reached by Jackson, so no second storage-specific validator is introduced.

- [ ] **Step 5: Run the focused tests and confirm the model is green**

~~~powershell
mvn.cmd -q -Dtest=OptimizationWorkItemDomainTest,OptimizationWorkItemStoreTest test
~~~

Expected: all focused tests pass, including existing persistence and duplicate-key tests.

### Task 2: Bind suite context to creation, lifecycle copies, and evidence matching

**Files:**
- Modify: apps/api/src/main/java/com/huawei/skillcenter/quality/OptimizationWorkItemService.java
- Modify: apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemServiceTest.java
- Modify: apps/api/src/test/java/com/huawei/skillcenter/quality/OptimizationWorkItemControllerTest.java

**Interfaces:**
- OptimizationWorkItemService.create writes the normalized request suite context into the created record.
- OptimizationWorkItemService.copy preserves current.suiteId() and current.suiteVersion() for every transition and evidence bind.
- sameContext gains evidence suiteId and suiteVersion parameters and enforces the legacy/pinned rules.
- Pinned Benchmark matching calls BenchmarkService.list(skillId, dataSource, runtimeId, mcpServerId, llmProviderId, suiteId, suiteVersion); legacy blank work items call the existing five-argument overload to preserve old behavior.

- [ ] **Step 1: Write failing service tests for creation and lifecycle preservation**

Update createRequest() to use the new request overload with suite-a and suite-v1, and add assertions:

~~~java
@Test
void createsAndPreservesPinnedSuiteContextAcrossLifecycle() {
    when(suggestions.suggestions(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(List.of(suggestion()));
    Actor actor = new Actor("admin", "admin");

    OptimizationWorkItem item = service.create(pinnedCreateRequest(), actor, "req-1");
    assertThat(item.suiteId()).isEqualTo("suite-a");
    assertThat(item.suiteVersion()).isEqualTo("suite-v1");

    item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("PLANNED", "", ""), actor, "req-2");
    item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("IN_PROGRESS", "", ""), actor, "req-3");
    item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("ABANDONED", "", "暂缓"), actor, "req-4");
    item = service.transition(item.workItemId(), new OptimizationWorkItemStatusRequest("OPEN", "", ""), actor, "req-5");

    assertThat(item).extracting(OptimizationWorkItem::suiteId, OptimizationWorkItem::suiteVersion)
            .containsExactly("suite-a", "suite-v1");
}
~~~

- [ ] **Step 2: Write failing evidence mismatch tests**

Add one service test per evidence type. Set the work item to READY_FOR_EVALUATION with candidate 1.1.0, then provide records with the same Skill/version/environment but suite mismatch:

~~~java
@Test
void rejectsEvaluationRunFromAnotherSuiteVersion() {
    when(evaluations.find("run-other-suite")).thenReturn(completedRun("other-suite", "suite-v1"));

    assertThatThrownBy(() -> service.bindEvidence(itemIdReadyForEvaluation(),
            new OptimizationWorkItemEvidenceRequest("EVALUATION_RUN", "run-other-suite", ""), admin(), "req"))
            .isInstanceOf(OptimizationWorkItemEvidenceException.class)
            .hasMessage("evaluation evidence does not match candidate context");
}
~~~

Repeat the assertion for QUALITY_SNAPSHOT with evaluations.findSnapshot(...) and for BENCHMARK with a BenchmarkResult carrying the mismatched suite fields. Add positive cases proving same-suite/same-version evidence binds successfully. Add a legacy work-item case proving a complete evidence record from any suite remains accepted through the existing five-argument Benchmark overload.

- [ ] **Step 3: Run the focused service tests and confirm the failures**

~~~powershell
mvn.cmd -q -Dtest=OptimizationWorkItemServiceTest test
~~~

Expected: new tests fail because the service does not yet copy or compare suite context and the pinned Benchmark overload is not used.

- [ ] **Step 4: Implement creation and copy propagation**

In create(...):

1. Normalize and validate the request pair using one private normalizeSuiteContext helper or equivalent local pair validation.
2. Pass the normalized values into the full OptimizationWorkItem constructor.
3. Add only suiteId and suiteVersion to the creation audit metadata when both are nonblank.

In copy(...), append the current suite values at the corresponding constructor positions. Do not accept suite values from status or evidence requests.

- [ ] **Step 5: Implement exact evidence matching with legacy compatibility**

Change the three evidence branches to pass run.suiteId()/run.suiteVersion() and snapshot.suiteId()/snapshot.suiteVersion() into the context matcher. The matcher must:

~~~java
private boolean suiteMatches(String itemSuiteId, String itemSuiteVersion,
                             String evidenceSuiteId, String evidenceSuiteVersion) {
    boolean itemPinned = !itemSuiteId.isBlank() || !itemSuiteVersion.isBlank();
    boolean evidencePinned = !normalizeOptional(evidenceSuiteId).isBlank()
            || !normalizeOptional(evidenceSuiteVersion).isBlank();
    if (itemPinned && (itemSuiteId.isBlank() || itemSuiteVersion.isBlank())) return false;
    if (evidencePinned && (normalizeOptional(evidenceSuiteId).isBlank()
            || normalizeOptional(evidenceSuiteVersion).isBlank())) return false;
    if (!itemPinned) return true;
    return itemSuiteId.equals(normalizeOptional(evidenceSuiteId))
            && itemSuiteVersion.equals(normalizeOptional(evidenceSuiteVersion));
}
~~~

Keep the existing Skill, candidate version, data source, Runtime, MCP and LLM predicates. For Benchmark, use the seven-argument overload only for pinned work items; filter the returned result by benchmark ID and candidate version, and reject a result whose own suite pair is partial before accepting it. Keep all failures as OptimizationWorkItemEvidenceException with the existing safe messages.

- [ ] **Step 6: Update controller contract tests for the new response fields**

Construct the controller test item with suite-a/suite-v1, send those fields in the POST JSON, and assert $.data.suiteId and $.data.suiteVersion. Keep the existing role and stable-error tests unchanged.

- [ ] **Step 7: Run API quality tests**

~~~powershell
mvn.cmd -q -Dtest=OptimizationWorkItemDomainTest,OptimizationWorkItemStoreTest,OptimizationWorkItemServiceTest,OptimizationWorkItemControllerTest,BenchmarkServiceEnvironmentTest test
~~~

Expected: all listed tests pass, including old constructor callers and both legacy and pinned evidence paths.

### Task 3: Propagate and display the context in Quality Center

**Files:**
- Modify: apps/web/src/QualityCenterView.jsx
- Modify: apps/web/tests/quality-center-view.test.mjs
- Inspect only: apps/web/src/api/skillApi.js (generic JSON methods should require no change)

**Interfaces:**
- createOptimizationWorkItem sends suiteId and suiteVersion from the current selected state.
- The create form is disabled when the selected pair is not present in the loaded suite list.
- Each work-item row displays either 套件 <suiteId> · 版本 <suiteVersion> or 未锁定套件版本.

- [ ] **Step 1: Write failing Web tests for payload and display**

In the lifecycle test, make listQualitySuites return an explicit enabled suite:

~~~js
listQualitySuites: async () => ({
  data: [{ id: "smoke", name: "Smoke", version: "smoke-v1", enabled: true }],
}),
~~~

Extend the expected create payload:

~~~js
assert.deepEqual(created[0], {
  skillId: "demo-skill", sourceVersion: "2.0.0", suggestionId: "runtime-data", hypothesis: "降低 P95",
  ownerId: "admin", dataSource: "mock", runtimeId: "", mcpServerId: "", llmProviderId: "",
  suiteId: "smoke", suiteVersion: "smoke-v1",
});
~~~

Add a row assertion for 套件 smoke · 版本 smoke-v1, and add a legacy row fixture with empty suite fields asserting 未锁定套件版本.

- [ ] **Step 2: Run the focused Web test and confirm failure**

~~~powershell
npm.cmd test -- --test-name-pattern="optimization work item|quality center turns"
~~~

Expected: the payload and text assertions fail before the component change.

- [ ] **Step 3: Implement request propagation and safe display**

In createOptimizationWorkItem, append suiteId and suiteVersion. Derive suiteSelectionAvailable from the loaded suites list and exact current pair; disable the create button when it is false. Keep the existing default values only as initial state, never as proof that a suite was loaded. In the row metadata, render the locked pair only when both fields are nonblank; otherwise render 未锁定套件版本. Do not add evidence content to the context label.

- [ ] **Step 4: Run the focused Web tests and build**

~~~powershell
npm.cmd test -- --test-name-pattern="optimization work item|quality center turns"
npm.cmd run build
~~~

Expected: focused tests pass and the Vite build completes.

### Task 4: Update lifecycle status documentation and perform full verification

**Files:**
- Modify: docs/project/remaining-coding-tasks-status.md
- Modify: docs/project/M11-regression-review-status.md

- [ ] **Step 1: Add the completed capability and test counts**

Record that optimization work items now pin evaluation suite context, legacy records remain compatible, and evidence matching covers Evaluation Run, Quality Snapshot, and Benchmark. Preserve the existing status-document style and update counts only from actual verification output.

- [ ] **Step 2: Run the full API suite**

~~~powershell
Set-Location apps/api
mvn.cmd -q test
~~~

Expected: the full API suite passes; record the exact test count from Maven output.

- [ ] **Step 3: Run the full Web suite**

~~~powershell
Set-Location apps/web
npm.cmd test
npm.cmd run build
~~~

Expected: all Web tests pass and the production build completes.

- [ ] **Step 4: Run repository diff hygiene checks**

~~~powershell
Set-Location D:\github\SkillCenter
git diff --check
git status --short -- apps/api/src/main/java/com/huawei/skillcenter/quality apps/api/src/test/java/com/huawei/skillcenter/quality apps/web/src/QualityCenterView.jsx apps/web/tests/quality-center-view.test.mjs docs/project/remaining-coding-tasks-status.md docs/project/M11-regression-review-status.md docs/superpowers/specs/2026-08-24-optimization-work-item-evaluation-context-design.md docs/superpowers/plans/2026-08-24-optimization-work-item-evaluation-context.md
~~~

Expected: no whitespace errors; only intended files from this plan appear in the scoped status output, alongside any already-dirty files outside the scope. Do not reset, checkout, delete, or commit unrelated work.

- [ ] **Step 5: Review against the specification before handoff**

Check every acceptance item in Sections 8 and 9 of the spec: model pair validation, old JSON compatibility, immutable propagation, three evidence mismatch classes, legacy behavior, stable error contract, Quality Center payload/display, and absence of sensitive content persistence. Report any missing verification instead of claiming completion.

