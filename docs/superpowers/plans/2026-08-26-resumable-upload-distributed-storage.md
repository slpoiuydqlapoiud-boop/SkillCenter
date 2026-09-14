# Resumable Upload Distributed Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有上传 API 和安全门禁的前提下，为可恢复 Skill ZIP 上传提供显式的本地/分布式存储端口，并用 Redis + S3-compatible 对象存储实现跨实例会话恢复。

**Architecture:** `ResumablePackageUploadService` 保持领域门面和既有错误契约，所有会话/分片操作下沉到 `ResumableUploadStore`。Local store 继续使用受控临时文件；distributed store 使用 Redis 元数据 reservation/commit CAS 与对象存储分片对象，两者通过显式配置选择，distributed 未 ready 时拒绝新写入且不回退 local。

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data Redis `StringRedisTemplate`, Java `HttpClient`, S3 Signature V4, JUnit 5, AssertJ, MockMvc, Maven。

**Spec:** `docs/superpowers/specs/2026-08-26-resumable-upload-distributed-storage-design.md`

## Implementation status (2026-08-26)

The implementation and regression coverage for Tasks 1–6 are now present in the working tree. The code uses the existing package-upload boundaries: `ResumableUploadStore` contains the metadata/chunk contracts, `S3ObjectClient` is the reusable object boundary, and `S3CompatibleArtifactStorage` provides the distributed object implementation. The distributed store now includes lease recovery/renewal, conditional abort, idempotent-content conflict detection, cleanup of uncommitted objects, and fail-closed Redis + object-storage readiness. Upload readiness is also included in `PlatformReadinessService`.

Verification evidence: `mvn -q -DforkCount=0 test` from `apps/api` completed with `974` tests, `0` failures, `0` errors, and `43` Docker capability skips; `git diff --check` completed successfully. Real Redis/S3 multi-instance integration, orphan-object load testing, and production migration acceptance remain external handoff items.

The original file map names some conceptual ports separately; the shipped implementation intentionally keeps the public contract smaller by using the nested records and `S3ObjectClient` boundary above. No isolated commit was created for this increment because the workspace already contained mixed user changes.

## Global Constraints

- API 路径、`UploadProgress` 字段和现有 `UPLOAD_*` 错误语义保持兼容，Web 不感知后端存储类型。
- 默认 `skill-center.package-upload-backend=local`，本地行为继续支持单机开发和现有测试。
- distributed 模式的 Redis、对象存储、凭据引用、TTL、容量和 reservation 租约必须显式配置；任一依赖未配置或不可达时 fail-closed，禁止静默回退。
- Redis 与对象存储不共享事务；分片必须遵循 reservation → conditional object write → commit CAS，两阶段失败不得推进 `receivedBytes`。
- 错误响应、日志、审计和 readiness 不得暴露凭据、endpoint、绝对路径、分片正文或上游异常原文。
- 每项生产代码变更先写失败测试并观察 RED，再写最小实现；每个任务独立运行测试并提交。

## File Map

- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadStore.java`: 上传会话/分片存储端口和边界 records。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/LocalResumableUploadStore.java`: 当前临时文件、TTL、容量和会话锁的本地实现。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/DistributedResumableUploadStore.java`: Redis 元数据与对象分片的组合实现。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadMetadataStore.java`: Redis/本地元数据操作端口。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/RedisResumableUploadMetadataStore.java`: reservation、commit、TTL、容量和清理 Lua 脚本。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadChunkStorage.java`: 分片对象写入/读取/删除端口。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/S3ResumableUploadChunkStorage.java`: S3-compatible 上传临时对象适配器。
- `apps/api/src/main/java/com/huawei/skillcenter/distribution/S3ObjectClient.java`: 从制品存储中提取的通用 S3 对象 HTTP/签名边界。
- `apps/api/src/main/java/com/huawei/skillcenter/distribution/S3CompatibleArtifactStorage.java`: 改为复用 `S3ObjectClient`，保持现有制品 API。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadBackendConfiguration.java`: local/distributed 条件装配与配置边界。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadReadiness.java`: 脱敏状态和稳定 reason code。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadReadinessController.java`: 管理员只读 readiness API。
- `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumablePackageUploadService.java`: 保留 API 门面，委托 store，保持现有验证/审核调用链。
- `apps/api/src/main/resources/application.yml`: 后端选择、Redis key 前缀、对象存储上传前缀和 reservation 配置。
- `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`: 新增分布式上传不可用/冲突错误的脱敏映射。
- `apps/api/src/test/java/com/huawei/skillcenter/packageupload/*`: store contract、Redis script、S3 chunk、双实例和 API 回归测试。
- `apps/api/src/test/java/com/huawei/skillcenter/distribution/*`: `S3ObjectClient` 重构后的制品存储回归。
- `docs/project/remaining-coding-tasks-status.md`: 增量结果、生产边界和验证计数。

### Task 1: 抽取上传存储端口并保留本地语义

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/LocalResumableUploadStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumablePackageUploadService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/LocalResumableUploadStoreTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/ResumablePackageUploadServiceTest.java`

**Interfaces:**
- Produces `ResumableUploadStore` with `create(CreateRequest)`, `load(uploadId, ownerId)`, `append(AppendRequest)`, `complete(uploadId, ownerId)`, `discard(uploadId, ownerId)`, `cleanupExpired(Instant)` and `readiness()`.
- `AppendRequest` carries `uploadId`, `ownerId`, `expectedStart`, `end`, `totalBytes` and `byte[] content`; store returns `UploadProgress` or a typed store exception.
- `CompletedUpload` continues to expose only a controlled `Path` to the service; no path is returned by API or persistence.

  ```java
  interface ResumableUploadStore {
      UploadProgress create(CreateRequest request);
      UploadProgress load(String uploadId, String ownerId);
      UploadProgress append(AppendRequest request);
      CompletedUpload complete(String uploadId, String ownerId);
      void discard(String uploadId, String ownerId);
      int cleanupExpired(Instant now);
      ResumableUploadReadiness readiness();
  }
  ```

- [ ] **Step 1: Write the failing contract tests**

  Add tests that construct a local store, create a session, append sequential chunks, resume from progress, complete, discard, expire an idle session, enforce session/byte capacity, and verify another service instance cannot use the owner’s session. Assert the service still emits `UPLOAD_*` exceptions rather than leaking store exceptions.

- [ ] **Step 2: Run the focused tests to verify RED**

  Run `mvn -q -Dtest=LocalResumableUploadStoreTest,ResumablePackageUploadServiceTest test` from `apps/api`. Expected: compilation or missing-port failures because the service still owns the `ConcurrentMap` implementation.

- [ ] **Step 3: Move the existing local implementation behind the port**

  Move the current session map, `capacityLock`, temp-file append, `lastActivityMillis`, cleanup and owner checks into `LocalResumableUploadStore`. Keep all current validation bounds and use the port records to return the same `UploadProgress` values. Make the service a thin adapter that converts typed store failures to `ResumablePackageUploadException`.

- [ ] **Step 4: Run focused tests to verify GREEN**

  Run the same Maven command. Expected: all local store and service tests pass, including the existing resumable upload controller behavior.

- [ ] **Step 5: Commit the isolated local-port extraction**

  Run `git add apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadStore.java apps/api/src/main/java/com/huawei/skillcenter/packageupload/LocalResumableUploadStore.java apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumablePackageUploadService.java apps/api/src/test/java/com/huawei/skillcenter/packageupload/LocalResumableUploadStoreTest.java apps/api/src/test/java/com/huawei/skillcenter/packageupload/ResumablePackageUploadServiceTest.java` and commit with `refactor: isolate resumable upload storage port`.

### Task 2: Extract the reusable S3 object boundary

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/S3ObjectClient.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/S3ObjectMetadata.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/S3CompatibleArtifactStorage.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/S3ObjectClientTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/S3CompatibleArtifactStorageTest.java`

**Interfaces:**
- `S3ObjectClient.putIfAbsent(String key, Path source, Map<String,String> metadata)`, `putIfAbsent(String key, byte[] content, Map<String,String> metadata)`, `get(String key)`, `head(String key)`, `delete(String key)`, and `probe()` own signing, bounded HTTP, credentials and sanitized transport errors.
- `S3CompatibleArtifactStorage` continues implementing `ArtifactStorage` and uses the client for content-addressed immutable ZIPs; its public references and integrity checks do not change.

  ```java
  import java.nio.file.Path;

  interface S3ObjectClient {
      PutResult putIfAbsent(String key, Path source, Map<String, String> metadata);
      PutResult putIfAbsent(String key, byte[] content, Map<String, String> metadata);
      byte[] get(String key);
      S3ObjectMetadata head(String key);
      void delete(String key);
      ObjectStorageProbeResult probe();
  }
  ```

- [ ] **Step 1: Write failing client delegation and error-redaction tests**

  Add tests for successful conditional PUT/GET/HEAD/delete, HTTP 404 mapping, network failure mapping, credential absence, request timeout and preservation of SHA-256 metadata. Assert no endpoint or credential text appears in thrown/public errors.

- [ ] **Step 2: Run the focused S3 tests to verify RED**

  Run `mvn -q -Dtest=S3ObjectClientTest,S3CompatibleArtifactStorageTest test` from `apps/api`. Expected: missing `S3ObjectClient` compilation failures.

- [ ] **Step 3: Extract signing and bounded HTTP behavior**

  Move the existing Signature V4 canonical request, credential resolution, HTTP status mapping and readiness probe into `S3ObjectClient`; retain ZIP-specific verification and opaque reference parsing in `S3CompatibleArtifactStorage`.

- [ ] **Step 4: Run the focused S3 tests to verify GREEN**

  Run the same command. Expected: extracted client tests and all existing artifact storage tests pass without changing local/object-storage readiness semantics.

- [ ] **Step 5: Commit the reusable object boundary**

  Commit with `refactor: extract s3 object client boundary` after reviewing only the files listed in this task.

### Task 3: Implement Redis metadata reservation and commit CAS

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadMetadataStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/RedisResumableUploadMetadataStore.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/RedisUploadMetadata.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/RedisResumableUploadMetadataStoreTest.java`

**Interfaces:**
- `ResumableUploadMetadataStore.create`, `load`, `reserveChunk`, `commitChunk`, `markCompleted`, `discard`, `cleanupExpired` and `readiness` operate on `RedisUploadMetadata` and return typed results.
- `reserveChunk` accepts `(uploadId, ownerId, expectedStart, end, totalBytes, idempotencyKey, leaseMillis)` and returns `RESERVED`, `ALREADY_RESERVED`, `ALREADY_COMMITTED`, `OFFSET_CONFLICT`, `EXPIRED` or `UNAVAILABLE`.
- `commitChunk` accepts `(uploadId, reservationVersion, idempotencyKey, actualSize, sha256)` and advances the offset only for the matching reservation.

- [ ] **Step 1: Write failing fake-client contract tests**

  Build a deterministic in-memory fake metadata client shared by two store instances. Test create capacity reservation, owner isolation, one-winner reservation under concurrent calls, same-key retry idempotency, different-content conflict, lease expiry, commit CAS, completion and cleanup capacity release.

- [ ] **Step 2: Run the focused metadata tests to verify RED**

  Run `mvn -q -Dtest=RedisResumableUploadMetadataStoreTest test` from `apps/api`. Expected: missing metadata store types and no implementation.

- [ ] **Step 3: Implement bounded Redis records and Lua scripts**

  Use namespaced keys for metadata, reservation, expiry index and capacity. The reserve script must verify owner, status, total size, expected offset, capacity and idempotency key before writing a short lease. The commit script must verify reservation version and exact object size/hash before advancing `receivedBytes`; all script failures map to sanitized typed results.

  ```lua
  -- reserve: KEYS[1]=metadata, KEYS[2]=capacity, ARGV=owner,start,end,total,key,lease,now,version
  if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 'FORBIDDEN' end
  if redis.call('HGET', KEYS[1], 'receivedBytes') ~= ARGV[2] then return 'OFFSET_CONFLICT' end
  if redis.call('HGET', KEYS[1], 'reservationKey') == ARGV[5] then return 'ALREADY_RESERVED' end
  redis.call('HSET', KEYS[1], 'reservationKey', ARGV[5], 'reservationStart', ARGV[2],
      'reservationEnd', ARGV[3], 'reservationVersion', ARGV[8])
  redis.call('PEXPIRE', KEYS[1], ARGV[6])
  return 'RESERVED'
  ```

- [ ] **Step 4: Run metadata tests to verify GREEN**

  Run the same command and confirm both fake shared instances observe identical offsets and capacity. No test may require a live Redis container; the real Redis capability remains an integration gate.

- [ ] **Step 5: Commit the Redis metadata store**

  Commit with `feat: add redis resumable upload metadata store` after the contract test output is green.

### Task 4: Implement distributed chunk storage and composed store

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadChunkStorage.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/S3ResumableUploadChunkStorage.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/DistributedResumableUploadStore.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/S3ResumableUploadChunkStorageTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/DistributedResumableUploadStoreTest.java`

**Interfaces:**
- `ResumableUploadChunkStorage.putIfAbsent(uploadId, start, end, bytes, sha256)`, `open(uploadId, start, end)`, `delete(uploadId)`, `deleteChunk(...)`, and `readiness()` use only an opaque upload prefix.
- `DistributedResumableUploadStore` composes `ResumableUploadMetadataStore` and `ResumableUploadChunkStorage`; it exposes the same `ResumableUploadStore` port as the local implementation.

- [ ] **Step 1: Write failing chunk and two-instance tests**

  Test that a chunk is written once under `uploads/<safe-prefix>/<uploadId>/<start>-<end>`, duplicate identical PUT is reused, mismatched duplicate is rejected, and delete removes all chunk keys. Then create two distributed stores sharing fake metadata/chunk clients: instance A creates and writes the first chunk, instance B resumes, commits the second chunk, assembles the file and completes it.

- [ ] **Step 2: Run focused distributed tests to verify RED**

  Run `mvn -q -Dtest=S3ResumableUploadChunkStorageTest,DistributedResumableUploadStoreTest test`. Expected: missing chunk/store implementations.

- [ ] **Step 3: Implement reservation → object PUT → commit flow**

  Use deterministic idempotency key `uploadId + ":" + start + "-" + end`. On an existing reservation, validate the object before retrying commit. On orphaned object or failed CAS, leave no readable progress and let bounded cleanup remove the object. Completion streams chunks in ascending offset order into a controlled local temp file, verifies exact total size, and returns it only to the existing validation pipeline.

  ```java
  String chunkKey = uploadId + "/" + start + "-" + end;
  Reservation reservation = metadata.reserve(request, chunkKey, leaseMillis);
  chunkStorage.putIfAbsent(chunkKey, bytes, sha256(bytes));
  metadata.commit(reservation.version(), chunkKey, bytes.length, sha256(bytes));
  ```

- [ ] **Step 4: Run focused distributed tests to verify GREEN**

  Run the same command and assert no partial or orphaned chunk can be read through the store after failure; verify the returned completed path is deleted by `discard`.

- [ ] **Step 5: Commit the distributed store**

  Commit with `feat: support distributed resumable upload chunks`.

### Task 5: Add explicit backend selection, readiness and API integration

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadBackendConfiguration.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadReadiness.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumableUploadReadinessController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/packageupload/ResumablePackageUploadService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/ResumableUploadBackendConfigurationTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/ResumableUploadReadinessControllerTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/packageupload/PackageControllerTest.java`

**Interfaces:**
- `skill-center.package-upload-backend` accepts only `local` or `distributed`; unknown values fail application configuration rather than selecting local.
- `ResumableUploadReadinessController` exposes `GET /api/v1/admin/package-uploads/readiness`, protected by the existing admin role guard, returning backend identity, metadata/chunk status, stable reason codes and bounded capacity summary.
- `ResumablePackageUploadService` receives the selected `ResumableUploadStore`; all existing `/uploads` routes delegate through it without changing request/response fields.

  ```yaml
  skill-center:
    package-upload-backend: ${SKILL_CENTER_PACKAGE_UPLOAD_BACKEND:local}
    package-upload-redis-key: ${SKILL_CENTER_PACKAGE_UPLOAD_REDIS_KEY:skill-center:uploads}
    package-upload-object-prefix: ${SKILL_CENTER_PACKAGE_UPLOAD_OBJECT_PREFIX:skill-uploads}
    package-upload-reservation-lease-ms: ${SKILL_CENTER_PACKAGE_UPLOAD_RESERVATION_LEASE_MS:30000}
  ```

- [ ] **Step 1: Write failing configuration, readiness and API tests**

  Assert local is selected by default, distributed is selected only with explicit property, incomplete distributed dependencies produce `NOT_READY` and reject create, readiness hides endpoint/secret/path fields, and existing pause/resume/cancel API tests still pass through the store port.

- [ ] **Step 2: Run focused configuration/API tests to verify RED**

  Run `mvn -q -Dtest=ResumableUploadBackendConfigurationTest,ResumableUploadReadinessControllerTest,PackageControllerTest test`. Expected: missing backend beans/readiness endpoint assertions.

- [ ] **Step 3: Implement conditional beans and stable error mapping**

  Add explicit `@ConditionalOnProperty` local/distributed beans, validate positive TTL/capacity/lease values, surface `UPLOAD_STORAGE_UNAVAILABLE`, `UPLOAD_RESERVATION_CONFLICT` and `UPLOAD_BACKEND_NOT_READY` through the existing `ResumablePackageUploadException` envelope, and keep distributed mode from silently constructing local storage.

- [ ] **Step 4: Run focused configuration/API tests to verify GREEN**

  Run the same command and assert the existing ZIP validation, security scan, artifact storage, authorization and review submission path remains unchanged.

- [ ] **Step 5: Commit backend selection and readiness**

  Commit with `feat: expose resumable upload backend readiness`.

### Task 6: Documentation, full regression and production handoff

**Files:**
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/superpowers/plans/2026-08-12-internal-skill-center-requirements-roadmap.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Test: `scripts/verify-lifecycle.ps1` output and fresh Surefire reports

- [ ] **Step 1: Write the final contract assertions**

  Add verifier assertions for the backend selector, no-local-fallback rule, required distributed configuration, bounded readiness fields and named Docker capability skips. Keep local mode usable when Docker/Redis/object storage are unavailable.

- [ ] **Step 2: Run the focused verifier to verify RED**

  Run `pwsh -NoProfile -File scripts/verify-lifecycle.ps1 -SkipSmoke` and record the first missing contract or count mismatch before changing the verifier.

- [ ] **Step 3: Update documentation from evidence**

  Record exact API/Web counts, readiness behavior, local-vs-distributed configuration, real Redis/S3/HA limitations and the integration owner/runbook steps. Mark only platform-side checklist items proven by tests; leave real environment deployment and UAT open.

- [ ] **Step 4: Run final verification**

  Run `mvn -q test` from `apps/api`, `npm test` and `npm run build` from `apps/web`, `pwsh -NoProfile -File scripts/verify-lifecycle.ps1 -SkipSmoke` from the repository root, and `git diff --check`. Expected: zero failures/errors; Docker capability tests are reported as skips only when Docker is unavailable.

- [ ] **Step 5: Commit documentation and verifier changes**

  Commit with `docs: document distributed resumable upload handoff` only after all fresh verification commands return exit code 0.

## Self-Review Checklist

- [x] The spec's local/distributed selection, Redis reservation/commit, object chunk storage, readiness, fail-closed behavior, migration, and test requirements are covered by Tasks 1–6.
- [x] No task silently treats local JSON or local files as multi-instance production storage.
- [x] No task changes the existing Skill ZIP validation, security scan, artifact integrity, authorization or review workflow.
- [x] All interface names used by later tasks are defined in an earlier task or in the file map.
- [x] All task steps name a concrete file, command, expected result, and commit boundary.
