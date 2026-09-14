# Quality Evidence Retention Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent retention cleanup from deleting quality evidence that is still referenced by optimization or release lifecycle assets, while preserving existing APIs and fail-closed behavior.

**Architecture:** Add a governance-owned reference index that produces an immutable, fingerprinted protection snapshot. `RetentionService` freezes the snapshot at preview, re-reads and compares it at execution, then passes protection sets into quality and Benchmark stores. Existing constructors and no-argument cleanup methods delegate to empty protection sets.

**Tech Stack:** Java/Spring Boot, JUnit 5, existing in-memory and JDBC repository abstractions, React/Vite management console, Markdown project documentation.

**Spec:** `docs/superpowers/specs/2026-08-25-quality-evidence-retention-protection-design.md`

## Global Constraints

- Preserve unrelated user changes in the dirty worktree; do not reset, clean, or commit.
- Use stable error codes `RETENTION_EVIDENCE_PROTECTION_UNAVAILABLE` and `RETENTION_PROTECTION_CONFLICT`.
- Keep old constructors and old repository methods source-compatible.
- Do not expose complete evidence-reference lists in API responses.
- Every production change must be covered by focused tests before claiming completion.

## Task 1: Define the protection snapshot and reference-index contract

**Files:**
- Add `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionProtectionSnapshot.java`
- Add `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionEvidenceReference.java`
- Add `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionEvidenceReferenceIndex.java`
- Add focused tests under `apps/api/src/test/java/com/huawei/skillcenter/governance/`

- [x] Write tests for deterministic fingerprints, deduplication, immutable sets, and empty snapshots.
- [x] Define bounded reference normalization and stable snapshot metadata.
- [x] Implement SHA-256 fingerprinting over sorted normalized references.
- [x] Verify the focused governance tests.

## Task 2: Implement the lifecycle reference index

**Files:**
- Add `apps/api/src/main/java/com/huawei/skillcenter/governance/DefaultRetentionEvidenceReferenceIndex.java`
- Update dependency wiring/configuration as needed.
- Add tests covering work items, experiments, release gate snapshots, and dependency failures.

- [x] Index work-item evidence types `EVALUATION_RUN`, `QUALITY_SNAPSHOT`, and `BENCHMARK`.
- [x] Index experiment evaluation, quality snapshot, and Benchmark IDs.
- [x] Index release gate quality snapshot and compatibility matrix IDs.
- [x] Keep terminal historical records protected and ignore blank IDs.
- [x] Fail closed when any source cannot be read.
- [x] Verify implementation tests.

## Task 3: Add protected retention operations to stores

**Files:**
- Update `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceRepository.java`
- Update `apps/api/src/main/java/com/huawei/skillcenter/quality/QualityEvidenceStore.java` if required by the abstraction.
- Update `apps/api/src/main/java/com/huawei/skillcenter/benchmark/BenchmarkStore.java`
- Add/extend repository tests.

- [x] Add protected-set overloads while keeping old methods delegating to empty sets.
- [x] Cascade protected quality snapshots/runs/matrices according to the existing state validator.
- [x] Ensure protected records are excluded from counts and deletes.
- [x] Verify in-memory and JDBC-compatible abstraction behavior.

## Task 4: Integrate preview freeze, conflict detection, and fail-closed execution

**Files:**
- Update `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionPreview.java`
- Update `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionExecutionResult.java`
- Update `apps/api/src/main/java/com/huawei/skillcenter/governance/RetentionService.java`
- Update `apps/api/src/test/java/com/huawei/skillcenter/governance/RetentionServiceTest.java`

- [x] Add protected counts/fingerprint metadata with compatibility constructors.
- [x] Inject the real reference index through the full constructor and an empty index through legacy constructors.
- [x] Freeze protection metadata on preview and reuse only after fingerprint verification.
- [x] Reject changed references before any delete call.
- [x] Reject index failures with the stable unavailable error code.
- [x] Cover preservation, deletion, conflict, failure, and idempotency tests.

## Task 5: Update management UI and operational documentation

**Files:**
- Update `apps/web/src/AuditExportView.jsx` or the actual retention-preview component.
- Update relevant web tests under `apps/web/tests/`.
- Update `docs/project/remaining-coding-tasks-status.md`.
- Update `docs/project/M11-regression-review-status.md`.
- Update `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`.

- [x] Display protected quality/Benchmark/reference counts and the protection fingerprint.
- [x] Render stable conflict and unavailable states without suggesting deletion succeeded.
- [x] Document the evidence-chain invariant and operator response.
- [x] Update the roadmap only after verification proves the increment.

## Task 6: Full verification and handoff

- [x] Run focused API tests.
- [x] Run the full API test suite and record skips/failures.
- [x] Run the full web test suite and production build.
- [x] Run `scripts/verify-lifecycle.ps1 -SkipSmoke` and any available smoke checks.
- [x] Review the diff for accidental unrelated changes.
- [x] Report evidence, remaining limitations, and updated progress percentage.
