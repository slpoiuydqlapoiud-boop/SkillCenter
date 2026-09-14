# Optimization Experiment Release Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make optimization experiment conclusions a read-only prerequisite of publishing candidate Skill versions without bypassing existing quality gates or automatically changing lifecycle state.

**Architecture:** Extend the existing `QualityEvaluationReleaseGate` with an optional `OptimizationExperimentStore` read path. It evaluates matching experiments before compatibility-matrix and quality-snapshot checks, throwing the existing `QualityGateBlockedException` with stable reasons. Legacy versions without experiments retain current behavior.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Mockito, JSON-backed `OptimizationExperimentStore`.

**Spec:** `docs/superpowers/specs/2026-08-24-optimization-release-gate-design.md`

## Global Constraints

- Do not make network calls or enable external Provider adapters.
- Do not auto-publish, rollback, update work-item status, or mutate experiment state from the release gate.
- Preserve `QualityGateBlockedException` fields and existing API error mapping.
- Preserve legacy publishing when no matching optimization experiment exists.
- Evaluate the latest terminal experiment by `updatedAt`, then `experimentId` as a deterministic tie-breaker.

### Task 1: Define the failing release-gate contract tests

**Files:**
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/governance/QualityEvaluationReleaseGateTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/QualityEvaluationReleaseGateTest.java`

**Interfaces:**
- Consumes: existing `QualityEvaluationReleaseGate.ensurePublishable(String skillId, String version)` and `OptimizationExperimentStore.findAll(String skillId, String workItemId, String status)`.
- Produces: executable assertions for every stable optimization blocking reason and the promotion pass-through.

- [ ] **Step 1: Add fixtures for matching experiments and decisions**

Create test helpers that construct `OptimizationExperiment` records for `QUEUED`, `RUNNING`, `FAILED`, `CANCELLED`, and `COMPLETED`; construct a valid `OptimizationExperimentDecision` for both `PROMOTE_CANDIDATE` and non-promoting outcomes.

- [ ] **Step 2: Add tests for no experiment and active experiment behavior**

```java
@Test
void legacyVersionWithoutOptimizationExperimentRemainsPublishable() {
    when(experiments.findAll("skill-a", null, null)).thenReturn(List.of());
    assertThatCode(() -> gate.ensurePublishable("skill-a", "1.0.0")).doesNotThrowAnyException();
}

@Test
void activeOptimizationExperimentBlocksPublishingWithStableReason() {
    when(experiments.findAll("skill-a", null, null)).thenReturn(List.of(experiment(OptimizationExperimentStatus.RUNNING)));
    assertThatThrownBy(() -> gate.ensurePublishable("skill-a", "1.0.0"))
            .isInstanceOf(QualityGateBlockedException.class)
            .extracting(QualityGateBlockedException::reasons)
            .asList().containsExactly("OPTIMIZATION_EXPERIMENT_INCOMPLETE");
}
```

- [ ] **Step 3: Add tests for failed, cancelled, missing, blocked, and promoted decisions**

Assert the exact reasons `OPTIMIZATION_EXPERIMENT_FAILED`, `OPTIMIZATION_EXPERIMENT_CANCELLED`, `OPTIMIZATION_DECISION_REQUIRED`, and `OPTIMIZATION_DECISION_BLOCKED`; assert a promoted experiment reaches the existing quality/matrix gate and does not add an optimization reason.

- [ ] **Step 4: Run the focused tests and verify RED**

Run: `mvn.cmd -q -f apps/api/pom.xml -Dtest=QualityEvaluationReleaseGateTest test`

Expected: compilation/test failure because the gate does not yet accept or evaluate `OptimizationExperimentStore`.

### Task 2: Implement the read-only optimization release policy

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/QualityEvaluationReleaseGate.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/QualityEvaluationReleaseGateTest.java`

**Interfaces:**
- Consumes: `OptimizationExperimentStore.findAll`, `OptimizationExperimentStatus`, `OptimizationExperimentDecision`, and `QualityGateBlockedException`.
- Produces: an overloaded Spring constructor and `ensureOptimizationExperimentPublishable(String skillId, String version)` private policy invoked before existing checks.

- [ ] **Step 1: Add the optional store dependency and preserve old constructors**

Keep the one-argument constructor and two-argument `(QualityEvaluationService, QualityEvidenceRepository)` constructor. Add the Spring-injected three-argument constructor with `OptimizationExperimentStore` and assign `null` only in compatibility constructors.

- [ ] **Step 2: Implement deterministic matching and stable blocking**

Filter by candidate version, block any non-terminal match, select the latest terminal record by `updatedAt` and `experimentId`, then apply the reason rules from the spec. Do not call `store.replace`, `audit`, or any external service.

- [ ] **Step 3: Invoke the policy before existing matrix/snapshot checks**

Call the new private policy at the beginning of `ensurePublishable`; then leave the compatibility matrix and quality snapshot branches unchanged.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `mvn.cmd -q -f apps/api/pom.xml -Dtest=QualityEvaluationReleaseGateTest test`

Expected: all release-gate tests pass with zero failures.

### Task 3: Verify the publish path and document the lifecycle rule

**Files:**
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/governance/ReviewServiceTest.java`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`

**Interfaces:**
- Consumes: `ReviewService.approve`, the Spring `QualityReleaseGate`, and the stable error reasons from Task 2.
- Produces: regression coverage proving publish approval cannot bypass an unresolved optimization experiment and operator documentation for the new gate.

- [ ] **Step 1: Add a ReviewService regression test with an injected blocking gate**

Construct `ReviewService` with a `QualityReleaseGate` that throws `QualityGateBlockedException` for the candidate version; assert approval leaves the version in `pending_review` and no published timestamp is written. Keep the existing successful approval test unchanged.

- [ ] **Step 2: Update lifecycle and runbook documentation**

Record that `PROMOTE_CANDIDATE` is necessary but not sufficient: compatibility matrix, quality gate, manual review, and external integration gates still apply. Record the four optimization reasons and the no-auto-mutation rule.

- [ ] **Step 3: Run focused API regression tests**

Run: `mvn.cmd -q -f apps/api/pom.xml -Dtest=QualityEvaluationReleaseGateTest,ReviewServiceTest test`

Expected: all focused tests pass.

- [ ] **Step 4: Run the complete verification suite**

Run:

```powershell
mvn.cmd -q -f apps/api/pom.xml test
npm.cmd --prefix apps/web test
npm.cmd --prefix apps/web run build
git diff --check
```

Expected: API and Web tests have zero failures, Vite/Sites build exits 0, and `git diff --check` reports no whitespace errors.

