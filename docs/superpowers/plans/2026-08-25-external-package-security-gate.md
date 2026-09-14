# External Package Security Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Skill 包上传增加可替换的外部安全扫描 contract、required fail-closed gate 和管理员 readiness。

**Architecture:** 本地 `PackageSecurityScanService` 是第一道确定性安全门；`PackageSecurityScanCoordinator` 负责根据模式决定是否调用外部 scanner。默认 `ContractOnlyExternalPackageSecurityScanner` 不联网且永远不报告生产 READY，未来批准的真实适配器通过同一端口注入。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, MockMvc。

**Spec:** `docs/superpowers/specs/2026-08-25-external-package-security-gate-design.md`

## Global Constraints

- 外部模式只允许 `disabled` 或 `required`，默认 `disabled`。
- `required` 模式只有外部 `PASSED` 才能提交审核；未配置、contract-only、超时、异常和非法结果必须 fail-closed。
- 安全结果只允许状态、scanner provenance 和 `code/path/severity`，禁止原始响应、reason 正文、Prompt、Trace、凭据和命中内容。
- 不实现或伪造具体生产供应商；contract-only 不发起网络请求。

### Task 1: Freeze external scanner and result contracts

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ExternalPackageSecurityScanner.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ExternalPackageSecurityScannerHealth.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageSecurityScanResult.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageValidationResult.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageSecurityScannerContractTest.java`

**Interfaces:**
- `ExternalPackageSecurityScanner#scannerId(): String`, `#scannerVersion(): String`, `#health(): ExternalPackageSecurityScannerHealth`, `#scan(Path): PackageSecurityScanResult`.
- `PackageSecurityScanResult` exposes `status`, `scannerId`, `scannerVersion`, `findings`, with the existing two-argument constructor preserved.

- [x] **Step 1: Write failing tests** for scanner provenance, safe finding summaries, bounded statuses and contract-only health.
- [x] **Step 2: Run the focused test** and confirm missing types/accessors cause RED.
- [x] **Step 3: Implement the immutable contracts** with legacy constructor defaults and safe normalization.
- [x] **Step 4: Re-run the focused test** and confirm GREEN.

### Task 2: Implement coordinator and validation integration

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageSecurityScanCoordinator.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ContractOnlyExternalPackageSecurityScanner.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageSecurityExternalMode.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageValidationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/SecurityScanEvidence.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageSecurityScanCoordinatorTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageValidationServiceTest.java`

**Interfaces:**
- Coordinator constructor consumes local scanner, external scanner, and mode; `scan(Path)` returns the final safe result.
- Validation service uses coordinator and retains old constructors by wiring local-only disabled defaults.

- [x] **Step 1: Write failing tests** for local blocked short-circuit, disabled local pass, required contract-only block, external pass, external block and external timeout/error normalization.
- [x] **Step 2: Run focused tests** and observe RED before implementation.
- [x] **Step 3: Implement coordinator** with stable unavailable finding and no raw exception propagation.
- [x] **Step 4: Wire validation and scanner provenance** into `PackageValidationResult` and `SecurityScanEvidence.from`.
- [x] **Step 5: Run package upload and governance regressions** and confirm GREEN.

### Task 3: Add configuration and readiness endpoint

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageSecurityReadiness.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageSecurityReadinessController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageValidationService.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageSecurityReadinessControllerTest.java`
- Modify: `apps/api/src/test/java/com/huawei/skillcenter/api/SensitiveResponseContractTest.java`

**Interfaces:**
- `GET /api/v1/admin/package-security/readiness` requires admin and returns safe `PackageSecurityReadiness` fields only.
- Spring config reads `skill-center.package-security.external.mode` and injects the default contract-only scanner.

- [x] **Step 1: Write failing controller/config tests** for default disabled, required contract-only, admin-only access and redaction.
- [x] **Step 2: Run focused controller tests** and observe RED.
- [x] **Step 3: Implement readiness DTO/controller and Spring wiring.**
- [x] **Step 4: Run focused API tests** and confirm GREEN.

### Task 4: Verify and document production boundary

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/progress.md`

- [x] **Step 1: Run focused API tests and the unified verifier.**
- [x] **Step 2: Verify no external network call exists in contract-only mode and no sensitive fields appear in readiness/validation responses.**
- [x] **Step 3: Mark only the contract/gate portion complete; keep real external scanner, credentials, SLA, malware/dependency/license acceptance open.**
- [x] **Step 4: Run `git diff --check` and record exact test counts and capability skips.**
