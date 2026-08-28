# Search Refresh Transactional Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind search refresh event intents to the same PostgreSQL transaction as Skill governance and scope writes, closing the post-commit publication crash window while keeping JSON/local behavior unchanged.

**Architecture:** Extend the governance and scope persistence ports with an optional refresh-event list. The PostgreSQL implementations write those metadata-only events through the same `JdbcTemplate` and `TransactionTemplate` that commits the business aggregate; JSON implementations ignore the optional list. Services still publish the in-process event after a successful write so the local coordinator refreshes immediately, while the PostgreSQL journal listener remains an idempotent compatibility safety net and the existing relay/cursor provides at-least-once delivery.

**Tech Stack:** Java 21, Spring Boot 3.4, JdbcTemplate, Spring `TransactionTemplate`, Flyway, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-27-skill-search-projection-design.md`

## Global Constraints

- Events contain only `skillId`, non-negative `sourceRevision`, and bounded stable `reasonCode`; no Prompt, content, credentials, tokens, paths, or raw exceptions.
- PostgreSQL selectors remain explicit; JSON remains the default and must not require a datasource or silently switch backends.
- Outbox insertion is part of the business transaction; an outbox failure rolls back the PostgreSQL business write.
- In-process publication remains after a successful business write and is not used as the durability guarantee.
- Duplicate event keys remain harmless and relay delivery remains at-least-once.
- All errors exposed outside the persistence boundary use existing stable error semantics.

---

### Task 1: Freeze the persistence-port event contract

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStateRepository.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillScopeRepository.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/GovernanceStoreRepositoryTest.java`

**Interfaces:**
- Add a default `replace(long expectedRevision, GovernanceSnapshot snapshot, List<SkillSearchRefreshEvent> refreshEvents)` that delegates to the existing two-argument method.
- Add default `create(SkillScope scope, SkillSearchRefreshEvent refreshEvent)` and `replace(SkillScope scope, int expectedRevision, SkillSearchRefreshEvent refreshEvent)` methods that delegate to existing methods.

- [ ] **Step 1: Write the failing routing tests**

Add a fake `GovernanceStateRepository` that records the event list passed by `GovernanceStore`, then call the new event-aware `GovernanceStore.updateReview` and assert the exact event key is forwarded. Add an event-aware fake `SkillScopeRepository` test at the service boundary and assert the scope mutation forwards the same event.

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=GovernanceStoreRepositoryTest,SkillAuthorizationServiceTest" test`

Expected: compilation failure because the event-aware persistence methods and service overloads do not yet exist.

- [ ] **Step 3: Add backward-compatible default port methods**

Import `SkillSearchRefreshEvent` and `List`, add the three default methods described above, and leave existing implementors source-compatible.

- [ ] **Step 4: Run the focused tests again**

Run the same Maven command. Expected: the port compilation succeeds, while routing assertions remain red until Tasks 2 and 3 wire services.

### Task 2: Route governance review and lifecycle intents before commit

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/VersionLifecycleService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/GovernanceStoreRepositoryTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/ReviewServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/VersionLifecycleServiceTest.java`

**Interfaces:**
- Preserve existing public overloads and delegate them to event-aware variants with `List.of()`.
- Add `GovernanceStore.updateReview(..., SkillSearchRefreshEvent refreshEvent)` and `GovernanceStore.transitionVersion(..., SkillSearchRefreshEvent refreshEvent)`.
- Make `GovernanceStore.persist` pass the event list to `GovernanceStateRepository.replace`.

- [ ] **Step 1: Add failing event-routing assertions**

For the publish path, construct `new SkillSearchRefreshEvent(version.skillId(), sourceRevision, "VERSION_PUBLISHED")` before the governance mutation, pass it to `store.updateReview`, and assert the fake repository receives it before the service publishes the local event. For lifecycle withdrawal/deprecation, assert the event key received by the repository equals the event later published.

- [ ] **Step 2: Run the tests and confirm the expected failure**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=GovernanceStoreRepositoryTest,ReviewServiceTest,VersionLifecycleServiceTest" test`

Expected: compilation or assertion failure because the event-aware methods are not wired.

- [ ] **Step 3: Implement the minimal routing**

Pass the event only for catalog-affecting transitions: published review approval and lifecycle deprecate/withdraw. Keep security-review, rejection, pending-version, notification, and local JSON semantics unchanged.

- [ ] **Step 4: Run the focused tests**

Run the same Maven command. Expected: all selected tests pass and the JSON tests continue to observe the original behavior.

### Task 3: Bind PostgreSQL governance writes to the outbox

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/JdbcGovernanceStateRepository.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/JsonGovernanceStateRepository.java` only if compilation requires an explicit override
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/JdbcGovernanceStateRepositoryTest.java` or the existing PostgreSQL repository contract test

**Interfaces:**
- Inject `ObjectProvider<SkillSearchRefreshEventStore>` into the PostgreSQL repository so the event feature remains optional.
- Override the three-argument `replace` and append each event through the shared `JdbcSkillSearchRefreshEventStore` while inside the existing `TransactionTemplate` callback.

- [ ] **Step 1: Write the failing JDBC transaction contract test**

Use a transaction-aware fixture to assert that a governance replacement with one refresh event invokes the event-store append from inside the same transaction callback, and that an event-store exception causes the replacement to fail rather than returning a committed governance state.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=JdbcGovernanceStateRepositoryTest" test`

Expected: compilation failure or the event store is not invoked by the existing two-argument replacement path.

- [ ] **Step 3: Implement the transaction-bound append**

Resolve the optional store once per call. Inside the existing `transactions.execute` lambda, perform the locked aggregate update and append the bounded event list before returning. Do not catch the outbox persistence exception as a successful governance write.

- [ ] **Step 4: Run the focused JDBC contract**

Run the same Maven command. Expected: success for commit and rollback assertions, with no endpoint or exception details exposed.

### Task 4: Bind PostgreSQL Skill scope writes to the outbox

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/access/JdbcSkillScopeStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillAuthorizationService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/JdbcSkillScopeStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/SkillAuthorizationServiceTest.java`

**Interfaces:**
- Inject the optional `SkillSearchRefreshEventStore` into `JdbcSkillScopeStore` without changing existing direct constructors.
- Append the scope event inside the same transaction used by `create` or `replace`.

- [ ] **Step 1: Write the failing scope transaction contract**

Assert that an event-aware scope create/replace sends the event to the outbox and that a failing append fails the scope mutation. Assert JSON test fixtures still use the default methods and do not require JDBC.

- [ ] **Step 2: Run the focused scope tests and verify RED**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=JdbcSkillScopeStoreTest,SkillAuthorizationServiceTest" test`

Expected: missing event-aware overload or missing outbox invocation.

- [ ] **Step 3: Implement the minimal transaction-bound scope wiring**

Create the `SKILL_SCOPE_SAVED` event before the JDBC mutation, pass it through the repository port, and retain the post-commit in-process event publication for the local coordinator.

- [ ] **Step 4: Run focused scope tests**

Run the same Maven command. Expected: all selected tests pass.

### Task 5: Validate relay compatibility, migration gate, and full regression

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/search/SkillSearchRefreshEventJournal.java` only if duplicate fallback behavior needs an explicit comment/guard
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/SkillSearchBackendReadinessService.java` only if the outbox requires a new schema gate
- Modify: `docs/superpowers/specs/2026-08-27-skill-search-projection-design.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/environment-dependencies.md`

- [ ] **Step 1: Add the migration/readiness and duplicate-delivery assertions**

Verify V17/V18 remain required when cross-instance events are enabled, the journal duplicate is harmless, and the relay advances the durable cursor only after consumer success and cursor persistence.

- [ ] **Step 2: Run all focused search and persistence tests**

Run: `mvn.cmd -q -DforkCount=0 "-Dtest=SkillSearch*,*GovernanceStore*,*SkillScope*,*Jdbc*" test`

Expected: all selected tests pass.

- [ ] **Step 3: Run the full API and Web verification**

Run in `apps/api`: `mvn.cmd -q -DforkCount=0 test`.

Run in `apps/web`: `npm.cmd test` and `npm.cmd run build`.

Expected: API has zero failures/errors; Docker-only capability skips remain explicit; Web tests and production build pass.

- [ ] **Step 4: Run repository hygiene checks**

Run from the repository root: `git diff --check` and inspect `git status --short` to ensure only explicitly selected files were committed.

- [ ] **Step 5: Commit the implementation**

Use separate commits for the transaction-bound implementation and documentation, for example:

```powershell
git add apps/api/src/main/java apps/api/src/test/java
git commit -m "feat: bind skill refresh outbox to postgres writes"
git add docs/project docs/superpowers/specs/2026-08-27-skill-search-projection-design.md docs/project/environment-dependencies.md
git commit -m "docs: record transactional search refresh boundary"
```

