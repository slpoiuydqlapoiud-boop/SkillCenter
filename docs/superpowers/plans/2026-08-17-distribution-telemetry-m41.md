# M4.1 Distribution and Installation Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a testable distribution loop for published Skills: short-lived one-time authorization, one-click/CLI/ZIP responses, artifact verification, installation state tracking, and batch installation/invocation event ingestion.

**Architecture:** Keep the existing Spring Boot API and local `GovernanceStore` persistence, but separate authorization, artifact access, installation state transitions, and telemetry ingestion behind focused services. Persist only token digests and event metadata; keep the existing event schemas and actor headers. Extend the existing React client with a distribution method selector and installation status refresh without changing the M1-M3 routes.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring MockMvc, JUnit 5, Jackson, local JSON snapshot persistence, React 19, Vite, Node built-in test runner.

## Global Constraints

- Skill packages are uploaded local ZIP files; the website never authors or edits a Skill.
- New distribution must only operate on `published` versions; `deprecated` is visible with a warning and `withdrawn`/unavailable versions are blocked.
- Authorization tokens are short-lived, single-use, bound to user/Skill/version/client/method, and stored only as a digest.
- Event ingestion must not persist prompt text, file contents, model inputs/outputs, or other sensitive payloads.
- Event deduplication key is `eventId`; same ID with different content is a conflict and must be rejected.
- Aggregation and event timestamps use `occurredAt`; received time is only for audit/data-quality metadata.
- Preserve `POST /api/v1/skills/{skillId}/installations`, `GET /api/v1/installations`, and `POST /api/v1/events/invocations` response compatibility.
- Every new write keeps the existing request-id envelope and audit conventions.
- This workspace has no Git metadata; commit steps are replaced by file-level verification and test checkpoints.

---

### Task 1: Add persistent authorization and distribution response models

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionAuthorization.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionResponse.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionAuthorizationService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionAuthorizationException.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceSnapshot.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/InstallationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/DistributionAuthorizationServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/DistributionControllerTest.java`

**Interfaces:**
- `DistributionAuthorizationService.issue(InstallManifest manifest, InstallationRecord installation, InstallationRequest request, Actor actor, Instant now)` returns `IssuedAuthorization` containing the one-time plaintext token, digest-backed record, and CLI command.
- `DistributionAuthorizationService.consume(String token, Instant now)` atomically marks a valid token consumed and returns its bound authorization record.
- `DistributionAuthorizationService.revokeForVersion(String skillId, String version, String reason)` revokes all unconsumed authorizations for the version.
- `DistributionResponse` contains `manifest`, `installationId`, `authorization` (`tokenId`, `expiresAt`, `method`, `downloadUrl`, `consumedAt`), and `cliCommand`.

- [ ] **Step 1: Write the failing service tests**

```java
@Test
void issuedTokenCanBeConsumedOnlyOnceBeforeExpiry() {
    IssuedAuthorization issued = service.issue(manifest(), installation(), request("cli"), actor(), clock.instant());

    assertThat(service.consume(issued.token(), clock.instant()).tokenId()).isEqualTo(issued.tokenId());
    assertThatThrownBy(() -> service.consume(issued.token(), clock.instant()))
            .isInstanceOf(DistributionAuthorizationException.class)
            .hasMessage("authorization has already been consumed");
}

@Test
void expiredTokenIsRejected() {
    IssuedAuthorization issued = service.issue(manifest(), installation(), request("manual-zip"), actor(), clock.instant());

    clock.advance(Duration.ofMinutes(16));

    assertThatThrownBy(() -> service.consume(issued.token(), clock.instant()))
            .isInstanceOf(DistributionAuthorizationException.class)
            .hasMessage("authorization has expired");
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=DistributionAuthorizationServiceTest test`

Expected: FAIL because the authorization model/service does not exist.

- [ ] **Step 3: Extend the snapshot and implement the minimal authorization service**

Add `List<DistributionAuthorization> authorizations` to `GovernanceSnapshot` with null-safe copy behavior. Add `addAuthorization`, `consumeAuthorization`, and `revokeAuthorizations` mutations to `GovernanceStore`; each mutation must persist atomically before updating `current`.

Use `SecureRandom` to create a 32-byte token, encode it with URL-safe Base64 without padding, and store `SHA-256(token)` only. Default expiry is 15 minutes. `consume` must perform the state check and mutation while holding the store write lock so two concurrent requests cannot consume the same token.

- [ ] **Step 4: Extend installation creation without breaking the existing manifest fields**

Change `InstallationService.createManifest` to return `DistributionResponse`. Keep the existing `manifest` object unchanged, create the `InstallationRecord` first, issue an authorization bound to that record, and return a CLI command of the form:

```text
skillctl install --skill <skillId>@<version> --token <short-lived-token>
```

For `one-click`, return the command as metadata but keep the existing HTTP `201` status. For `cli` and `manual-zip`, set the response method accordingly. Update `DistributionControllerTest` to assert `$.data.manifest`, `$.data.installationId`, `$.data.authorization.expiresAt`, and `$.data.cliCommand`.

- [ ] **Step 5: Add stable authorization errors and verify GREEN**

Map expired, consumed, revoked, malformed, and version-mismatch cases to `DISTRIBUTION_AUTHORIZATION_INVALID` with HTTP 410 for expired/consumed/revoked tokens and HTTP 400 for malformed tokens. Run the focused tests again and then `mvn -B -q -f apps/api/pom.xml -Dtest=DistributionAuthorizationServiceTest,DistributionControllerTest test`.

- [ ] **Step 6: Refactor only after all focused tests pass**

Extract token digesting and method normalization helpers if duplication remains; rerun the same focused tests. Do not change public field names during this refactor.

---

### Task 2: Implement authorization consumption and local ZIP artifact delivery

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactDownloadService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/distribution/ArtifactController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionAuthorizationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/distribution/DistributionService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/distribution/ArtifactControllerTest.java`

**Interfaces:**
- `ArtifactDownloadService.download(String skillId, String version, String token, Instant now)` returns a `Resource` plus media type and content length.
- `GET /api/v1/distribution/artifacts/{skillId}/{version}?token=...` consumes the authorization and returns an `application/zip` stream.

- [ ] **Step 1: Write failing MockMvc tests**

```java
@Test
void validAuthorizationDownloadsThePublishedZipAndConsumesToken() throws Exception {
    String token = issueForUploadedSkill("eox-query", "1.2.0", "manual-zip");

    mockMvc.perform(get("/api/v1/distribution/artifacts/eox-query/1.2.0")
                    .queryParam("token", token))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/zip"))
            .andExpect(header().string("X-Skill-SHA256", matchesPattern("[0-9a-f]{64}")));

    mockMvc.perform(get("/api/v1/distribution/artifacts/eox-query/1.2.0")
                    .queryParam("token", token))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.error.code").value("DISTRIBUTION_AUTHORIZATION_INVALID"));
}

@Test
void artifactDownloadRejectsAValidTokenForAnotherVersion() throws Exception {
    String token = issueForUploadedSkill("eox-query", "1.2.0", "manual-zip");

    mockMvc.perform(get("/api/v1/distribution/artifacts/eox-query/1.3.0")
                    .queryParam("token", token))
            .andExpect(status().isGone());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=ArtifactControllerTest test`

Expected: FAIL because no artifact endpoint exists.

- [ ] **Step 3: Implement local artifact resolution**

Resolve the authorization first, then locate the matching published `SkillVersion.artifactPath` from `GovernanceStore`. Reject missing files, directories, path traversal, non-ZIP paths, and versions not in `published` state with `ARTIFACT_NOT_FOUND` (404). Compute the response hash from the stored version metadata and expose it as `X-Skill-SHA256`; never accept a client-provided path.

- [ ] **Step 4: Consume authorization only after artifact validation**

Validate skill/version/method and artifact existence before the atomic consume mutation. A failed file lookup must leave the token reusable until expiry. Add an audit event `ARTIFACT_DOWNLOADED` with skill, version, method, and token ID, without recording the plaintext token.

- [ ] **Step 5: Verify GREEN and regression**

Run `mvn -B -q -f apps/api/pom.xml -Dtest=ArtifactControllerTest,DistributionControllerTest test`, then the full API test suite.

---

### Task 3: Add installation event state transitions and installation detail APIs

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InstallationEvent.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InstallationEventBatchRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InstallationEventService.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/InstallationEventController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/InstallationRecord.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/InstallationService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/InstallationController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/events/InstallationEventServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/events/InstallationEventControllerTest.java`

**Interfaces:**
- `InstallationEventService.ingest(InstallationEvent event)` returns `EventResult(eventId, accepted, duplicate, errorCode)`.
- `InstallationEventService.ingestBatch(InstallationEventBatchRequest batch)` returns per-event results and accepted/duplicate/rejected totals.
- `GET /api/v1/installations/{installationId}` returns the caller-visible `InstallationRecord`.
- `POST /api/v1/events/installations/batch` accepts `{batchId, schemaVersion, events[]}` and supports partial success.

- [ ] **Step 1: Write the state-transition and idempotency tests**

```java
@Test
void successfulInstallEventMovesRequestedInstallationToInstalled() {
    InstallationRecord requested = service.createRequested(...);

    EventResult result = service.ingest(new InstallationEvent(
            "1.0", eventId, occurredAt, "eox-query", "1.2.0",
            new Subject("user-1", "network-team"),
            new Client("codex", "1.0.0"), "device_123456789012", "install",
            "one-click", "success", null));

    assertThat(result.accepted()).isTrue();
    assertThat(store.findInstallation(requested.installationId()).orElseThrow().status()).isEqualTo("installed");
}

@Test
void duplicateInstallationEventDoesNotApplyTheTransitionTwice() {
    InstallationEvent event = validInstallEvent();

    assertThat(service.ingest(event).duplicate()).isFalse();
    assertThat(service.ingest(event).duplicate()).isTrue();
    assertThat(store.snapshot().installations()).hasSize(1);
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=InstallationEventServiceTest,InstallationEventControllerTest test`

Expected: FAIL because the event type, batch endpoint, and state mutation do not exist.

- [ ] **Step 3: Extend `InstallationRecord` compatibly**

Add `teamId`, `deviceId`, `method`, `lastEventId`, `lastErrorCode`, `installedAt`, and `removedAt`. Keep a legacy constructor with the existing 10 parameters so current tests and old JSON snapshots remain readable. Add `GovernanceStore.updateInstallation(InstallationRecord updated, AuditEvent audit)` and `findInstallation`.

- [ ] **Step 4: Implement strict transitions and per-event batch results**

Accept `install`, `upgrade`, `downgrade`, and `uninstall`; map successful install/upgrade/downgrade to `installed`, uninstall to `removed`, and failures to `failed` with `lastErrorCode`. Reject events whose Skill/version/client do not match the installation. Reject the same event ID with different serialized content as `EVENT_ID_CONFLICT`. Process events independently so one rejection does not roll back accepted events.

- [ ] **Step 5: Add role-aware detail query and verify GREEN**

Allow the requesting user to read only their own installation; reviewer/admin can read all. Return `INSTALLATION_NOT_FOUND` for missing or unauthorized records. Run focused tests, then all governance and event tests.

---

### Task 4: Add batch invocation ingestion with conflict-safe deduplication

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/EventBatchRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/events/EventBatchResponse.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEventService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/events/InvocationEventController.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/api/GlobalExceptionHandler.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/events/InvocationEventServiceTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/events/InvocationEventControllerTest.java`

**Interfaces:**
- `InvocationEventService.ingestBatch(EventBatchRequest batch)` returns `EventBatchResponse` with `batchId`, `accepted`, `duplicates`, `rejected`, and `results[]`.
- `POST /api/v1/events/invocations/batch` returns HTTP 202 when at least one event is accepted, 200 for an all-duplicate batch, and 400 only when the envelope itself is malformed.

- [ ] **Step 1: Write failing batch tests**

```java
@Test
void batchAcceptsValidEventsAndReportsDuplicateAndInvalidItemsIndividually() throws Exception {
    String body = batch(validInvocation("event-1"), validInvocation("event-1"), invalidInvocation("event-2"));

    mockMvc.perform(post("/api/v1/events/invocations/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.accepted").value(1))
            .andExpect(jsonPath("$.data.duplicates").value(1))
            .andExpect(jsonPath("$.data.rejected").value(1))
            .andExpect(jsonPath("$.data.results[2].errorCode").value("EVENT_SCHEMA_INVALID"));
}

@Test
void sameEventIdWithDifferentContentIsRejectedAsConflict() {
    service.ingest(validEvent("same-id", "success"));

    EventBatchResponse response = service.ingestBatch(batchOf(validEvent("same-id", "failure")));

    assertThat(response.rejected()).isEqualTo(1);
    assertThat(response.results().getFirst().errorCode()).isEqualTo("EVENT_ID_CONFLICT");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=InvocationEventServiceTest,InvocationEventControllerTest test`

Expected: FAIL because the batch request/response and conflict detection do not exist.

- [ ] **Step 3: Implement content fingerprints and batch processing**

Store a canonical Jackson serialization fingerprint alongside each event ID. For an existing ID, return duplicate only when the fingerprint matches; otherwise return `EVENT_ID_CONFLICT`. Validate each event through the existing schema rules and catch only per-item validation exceptions inside the batch loop.

- [ ] **Step 4: Preserve the single-event endpoint**

Keep `POST /api/v1/events/invocations` behavior unchanged. Delegate its validation and deduplication to the same service path, but retain its existing `accepted` and `duplicate` fields and status codes.

- [ ] **Step 5: Verify GREEN and full regression**

Run focused event tests, then `mvn -B -q -f apps/api/pom.xml test`.

---

### Task 5: Wire the M4.1 web client to distribution methods and installation status

**Files:**
- Modify: `apps/web/src/api/skillApi.js`
- Modify: `apps/web/src/App.jsx`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/tests/api-client.test.mjs`

**Interfaces:**
- `skillApi.createInstallation(skillId, input)` returns `DistributionResponse`.
- `skillApi.getInstallation(installationId)` calls `/api/v1/installations/{installationId}`.
- `skillApi.consumeDistributionToken(tokenId, token)` calls `/api/v1/distribution/authorizations/{tokenId}/consume`.
- `skillApi.downloadArtifact(skillId, version, token)` builds the artifact URL without sending token data to analytics.

- [ ] **Step 1: Write failing client contract tests**

```js
test("skillApi exposes distribution methods and installation detail", async () => {
  const calls = [];
  const api = createSkillApi((path, options) => {
    calls.push({ path, options });
    return Promise.resolve({ data: {} });
  });

  await api.createInstallation("eox-query", { clientType: "codex", clientVersion: "1.0.0", method: "cli" });
  await api.getInstallation("installation-1");
  await api.consumeDistributionToken("token-1", "short-token");

  assert.deepEqual(calls.map((call) => call.path), [
    "/api/v1/skills/eox-query/installations",
    "/api/v1/installations/installation-1",
    "/api/v1/distribution/authorizations/token-1/consume",
  ]);
  assert.equal(calls[0].options.body, JSON.stringify({ clientType: "codex", clientVersion: "1.0.0", method: "cli" }));
});
```

- [ ] **Step 2: Run the focused client test and verify RED**

Run: `npm.cmd test --prefix apps/web -- tests/api-client.test.mjs`

Expected: FAIL because the new API methods are not present.

- [ ] **Step 3: Add API methods and replace fire-and-forget install behavior**

Extend `createSkillApi` with the methods above. In `DetailView`, open an install dialog with method tabs (`one-click`, `cli`, `manual-zip`), client type/version fields, compatibility and permission summary, token expiry, SHA-256, and a copyable CLI command. On successful creation, store `installationId` and poll `getInstallation` every 2 seconds for at most 30 seconds; stop polling on `installed`, `failed`, `removed`, or timeout.

- [ ] **Step 4: Render installation records with the new fields**

Update `InstallationsView` to show method, client, requested time, current status, last error, and installed time. Keep the existing empty/loading/error states and use `requestId` from API errors in the toast.

- [ ] **Step 5: Add focused styles and verify GREEN**

Add only the dialog, method tabs, status chip, command block, and responsive layout styles required by the new states. Run `npm.cmd test --prefix apps/web` and `npm.cmd run build --prefix apps/web`.

---

### Task 6: Contract fixtures, smoke verification, and M4.1 handoff

**Files:**
- Create: `contracts/schemas/v1/event-batch.schema.json`
- Create: `contracts/schemas/v1/distribution-authorization.schema.json`
- Modify: `docs/project/M3-governance-loop-status.md`
- Create: `docs/project/M4.1-distribution-telemetry-status.md`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/api/ApiErrorContractTest.java`

- [ ] **Step 1: Write failing contract/error tests**

Add API error assertions for expired authorization (`410`, `DISTRIBUTION_AUTHORIZATION_INVALID`), missing artifact (`404`, `ARTIFACT_NOT_FOUND`), event ID conflict (`400`, `EVENT_ID_CONFLICT`), and unauthorized installation detail (`404`, `INSTALLATION_NOT_FOUND`).

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -B -q -f apps/api/pom.xml -Dtest=ApiErrorContractTest test`

Expected: FAIL until the new handlers and endpoints are wired.

- [ ] **Step 3: Add JSON Schemas**

Define the batch envelope with `batchId`, `schemaVersion`, and `events` (1–100 items), `additionalProperties: false`, and preserve the existing event schema as the item schema. Define authorization response fields with token ID, method, expiry, download URL, and optional consumed timestamp; never include a plaintext token in persisted examples.

- [ ] **Step 4: Document the delivered boundary and test evidence**

Record the implemented endpoints, state transitions, local-storage limitation, and exact commands/results in `docs/project/M4.1-distribution-telemetry-status.md`. Update the M3 status document's remaining-work section to point to M4.1 and leave M4.2 analytics explicitly open.

- [ ] **Step 5: Run the complete verification suite**

Run:

```powershell
mvn -B -q -f apps/api/pom.xml test
npm.cmd test --prefix apps/web
npm.cmd run build --prefix apps/web
```

For live smoke checks, start Spring Boot and Vite if they are not already running, then verify:

```text
POST /api/v1/skills/eox-query/installations -> 201 with authorization and CLI metadata
GET  /api/v1/installations/{id}              -> 200 with requested status
POST /api/v1/events/installations/batch      -> 202 with per-event results
POST /api/v1/events/invocations/batch       -> 202/200 with accepted/duplicate/rejected totals
GET  /api/v1/distribution/artifacts/...      -> 200 once, 410 on replay
```

- [ ] **Step 6: Final review checkpoint**

Confirm that all M4.1 acceptance criteria are evidenced by tests or smoke output, no sensitive payload fields were added, and M4.2 statistics work remains clearly separated.

## Self-review against the approved design

- Short-lived, one-time authorization: Tasks 1–2.
- One-click/CLI/ZIP distribution: Tasks 1–2 and Task 5.
- Installation state and installation batch events: Task 3.
- Invocation batch, partial success, duplicate and conflict handling: Task 4.
- Personal installation list/detail: Tasks 3 and 5.
- Audit/request-id/error contract: Tasks 1–4 and Task 6.
- 7/30/90/custom analytics, deprecation/withdrawal impact and notification UI are intentionally M4.2/M4.3 work, not hidden in this M4.1 plan.
