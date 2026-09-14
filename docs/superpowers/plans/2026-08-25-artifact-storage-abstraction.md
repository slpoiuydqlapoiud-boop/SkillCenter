# Skill Artifact Storage Abstraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Introduce a replaceable artifact storage port with immutable content-addressed local storage while preserving existing SkillVersion JSON and API compatibility.

**Architecture:** `ArtifactStorage` owns artifact write/verify/open operations. `LocalPackageStorage` becomes the default content-addressed adapter and accepts both new `local://` references and legacy filesystem references. Upload, catalog, Manifest, and download services depend on the port instead of resolving package paths themselves.

**Tech Stack:** Spring Boot 3, Java 21, JUnit 5, AssertJ, Spring `Resource`, Java NIO, ZIP streams.

**Spec:** `docs/superpowers/specs/2026-08-25-artifact-storage-abstraction-design.md`

## Global Constraints

- Keep `SkillVersion.artifactPath` as the serialized field for backward compatibility; new values are opaque references.
- Never expose local absolute paths, artifact content, credentials, or raw storage exceptions.
- Bound artifacts fail closed on missing, symlink, non-ZIP, unreadable, invalid-hash, or hash-mismatch conditions.
- Only empty artifact references may use the existing deterministic generated artifact path for legacy seed Skills.
- Do not claim real object storage, signed URLs, replication, retention, backup/PITR, capacity/SLO, or production readiness without target-environment evidence.

---

### Task 1: Define the storage port and immutable local write contract

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactStorage.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/StoredPackage.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/LocalPackageStorage.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/LocalPackageStorageTest.java`

- [x] Write tests for SHA-addressed opaque references, duplicate valid content reuse, and refusal to overwrite a corrupted existing digest object.
- [x] Run `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=LocalPackageStorageTest" test` and observe the expected missing-port/behavior failure.
- [x] Implement `ArtifactStorage`, `StoredPackage.reference()` with a compatibility `path()` accessor, and atomic local content-addressed writes under `sha256/<digest>.zip`.
- [x] Run the focused test again and verify all storage contract tests pass.

### Task 2: Centralize local reference resolution and integrity access

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/LocalPackageStorage.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactIntegrityVerifier.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/LocalPackageStorageTest.java`

- [x] Add tests for `local://sha256/<digest>.zip`, legacy absolute paths, legacy relative paths, and rejection of traversal/symlink references.
- [x] Implement `verify(reference, expectedSha256)` and `open(verifiedArtifact)` without returning the storage root or raw path through the port.
- [x] Keep existing `ArtifactIntegrityVerifier` as the single byte-level ZIP/SHA validator.
- [x] Run the focused storage tests and the existing integrity tests.

### Task 3: Route upload and governance records through the port

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/PackageController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ReviewService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/ReviewServiceTest.java`

- [x] Add a controller assertion that a successful upload records a `local://` opaque artifact reference and never returns a filesystem path.
- [x] Inject `ArtifactStorage` at the Spring boundary while preserving the existing constructor seam used by unit tests.
- [x] Store the returned reference in `StoredPackage`/`SkillVersion` without changing the JSON field name or existing review transitions.
- [x] Run focused upload/review tests and verify existing API error envelopes remain unchanged.

### Task 4: Route catalog, Manifest, and download through the port

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/skill/SkillCatalogService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactPackageService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactDownloadService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/ArtifactPackageServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/ArtifactControllerTest.java`

- [x] Add focused tests proving a bound opaque artifact is verified before metadata, catalog content, and download.
- [x] Use the storage resource for ZIP entry reads and downloads; preserve deterministic generation only for empty legacy references.
- [x] Verify an integrity failure leaves the distribution authorization unconsumed.
- [x] Run focused distribution/catalog tests.

### Task 5: Document production handoff and run the full gate

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/progress.md`

- [x] Record local content-addressed storage completion and the explicit object-storage handoff boundary.
- [x] Run `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 test` and record exact totals.
- [x] Run `npm.cmd test`, `npm.cmd run build`, `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-skill-lifecycle-projection.ps1`, and `git diff --check`.
- [x] Review the diff for path/content leakage and update the implementation plan evidence.
