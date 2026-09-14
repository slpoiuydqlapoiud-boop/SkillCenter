# 审核、发布与分发准入统一 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将审核完成、ReleaseRecord 晋级和 Skill 分发准入统一为可迁移、可审计的软件生命周期主链路。

**Architecture:** 新增只读 `ReleaseAdmissionService` 作为分发准入唯一策略入口；`LEGACY_COMPATIBLE` 保留无生产批次历史版本的行为，`CONTROLLED` 要求匹配的 PRODUCTION/PROMOTED 批次。审核最终批准后使用冻结的质量门禁快照幂等登记 STAGING 发布批次，实际晋级仍由现有 Release Control Plane 执行。

**Tech Stack:** Java 21, Spring Boot, Jackson JSON store, JUnit 5, Mockito, React/Node tests.

**Spec:** `docs/superpowers/specs/2026-08-24-review-release-admission-design.md`

## Global Constraints

- 不改变既有 `SkillVersion.status` 的 `published/deprecated/withdrawn` 语义。
- `LEGACY_COMPATIBLE` 默认兼容历史版本；`CONTROLLED` 必须显式配置。
- 生产准入只接受同一 Skill、版本和 SHA-256 的 `PROMOTED` ReleaseRecord。
- 失败、审批中、拒绝、哈希不匹配和未知外部执行状态必须 fail-closed。
- 不保存 Prompt、输入输出、Trace 正文、工具参数、凭据或 Provider 异常正文。
- 保留当前工作区既有改动，不执行 reset、checkout、删除或提交。

---

### Task 1: Build the admission decision model and policy service

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseAdmissionMode.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseAdmissionDecision.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseAdmissionException.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseAdmissionService.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/release/ReleaseAdmissionServiceTest.java`

**Interfaces:**
- `ReleaseAdmissionMode.from(String)` accepts only `LEGACY_COMPATIBLE` and `CONTROLLED`.
- `ReleaseAdmissionService.evaluate(String skillId, String version)` returns `ReleaseAdmissionDecision`.
- `ReleaseAdmissionService.requireDownloadable(String skillId, String version)` returns the allowed decision or throws `ReleaseAdmissionException`.

- [x] **Step 1: Write failing policy tests** for legacy history, controlled missing release, production not promoted, hash mismatch, and matching promoted release.
- [x] **Step 2: Run the focused test and confirm missing model/service failures.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=ReleaseAdmissionServiceTest" test`

- [x] **Step 3: Implement the bounded decision model and JSON-backed lookup.**

The service must select the version from `GovernanceStore`, inspect only PRODUCTION records from `ReleaseRecordStore`, and compare `SkillVersion.sha256()` with `ReleaseRecord.sha256()`. Legacy compatibility is allowed only when mode is `LEGACY_COMPATIBLE` and no PRODUCTION record exists.

- [x] **Step 4: Add stable exception mapping and rerun the focused policy tests.**

Expected: all policy cases pass and error messages contain no internal exception text.

### Task 2: Enroll approved reviews into STAGING release records

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/release/ReleaseService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/ReviewServiceReleaseEnrollmentTest.java`

**Interfaces:**
- `ReleaseService.requestFromApprovedVersion(SkillVersion version, ReleaseGateSnapshot gateSnapshot, Actor actor, String requestId)` creates or returns a STAGING `REQUESTED` record with idempotency key `review:{reviewId}:staging`.
- Review approval must call the enrollment method only after the governance update succeeds.
- Enrollment failure must add `RELEASE_STAGING_ENROLLMENT_FAILED` with only Skill/version/reason code metadata and must not expose exception text.

- [x] **Step 1: Write failing tests** for successful enrollment, idempotent repeated enrollment, frozen gate snapshot propagation, and safe enrollment failure audit.
- [x] **Step 2: Run the focused tests and confirm the enrollment API is absent.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=ReviewServiceReleaseEnrollmentTest" test`

- [x] **Step 3: Capture the gate snapshot during final review approval and invoke enrollment after `store.updateReview`.**

Keep existing high-risk security-review transitions unchanged; only the final `approved -> published` transition enrolls a release batch.

- [x] **Step 4: Rerun ReviewService, release, and quality gate tests.**

Run: `mvn.cmd -q -f apps/api/pom.xml "-Dtest=ReviewServiceTest,ReviewServiceReleaseEnrollmentTest,ReleaseServiceTest,QualityEvaluationReleaseGateTest" test`

### Task 3: Enforce one admission policy in manifest and artifact download

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactDownloadService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/release/ReleaseAdmissionDistributionTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/DistributionControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/ArtifactControllerTest.java`

**Interfaces:**
- Both distribution paths inject the same `ReleaseAdmissionService`.
- Manifest creation and ZIP download call `requireDownloadable(skillId, version)` before issuing authorization or consuming it.
- Existing withdrawn-version and artifact-not-found semantics remain unchanged.

- [x] **Step 1: Write failing tests** proving a PRODUCTION `REQUESTED`, `FAILED`, or hash-mismatched release blocks both manifest and download, while matching `PROMOTED` allows both.
- [x] **Step 2: Run the focused distribution tests and confirm they do not consult release admission yet.**
- [x] **Step 3: Add the shared admission call at the two distribution boundaries.**
- [x] **Step 4: Run focused distribution and release tests.**

### Task 4: Add controlled migration visibility to Quality Center and docs

**Files:**
- Modify: `apps/web/src/ReleaseControlPanel.jsx`
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/tests/quality-center-view.test.mjs`
- Test: `apps/web/tests/api-client.test.mjs`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-regression-review-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`

**Interfaces:**
- Release panel explains whether the current version is legacy-compatible or production-controlled when the API exposes the admission decision.
- Page load remains read-only; no automatic release or migration writes are triggered.

- [x] **Step 1: Write failing Web tests** for controlled-mode messaging and explicit production-release actions.
- [x] **Step 2: Add a read-only admission summary to the existing release panel.**
- [x] **Step 3: Document baseline migration, config switch, and rollback to legacy-compatible mode.**
- [x] **Step 4: Run Web tests and production build.**

### Task 5: Full verification and lifecycle audit

**Files:**
- No new production files; verify all changed files above.

- [x] **Step 1: Run `npm.cmd --prefix apps/web test`.**
- [x] **Step 2: Run `npm.cmd --prefix apps/web run build`.**
- [x] **Step 3: Run `mvn.cmd -q -f apps/api/pom.xml test` and aggregate Surefire XML.**
- [x] **Step 4: Run `git diff --check` and scan release/distribution code for sensitive fields.**
- [x] **Step 5: Confirm external Runtime/CD integrations remain CONTRACT_ONLY or Mock and do not claim production deployment.**
