# Provider HTTP Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 OpenClaw、DeepEval 和 Langfuse 的契约占位适配器升级为默认关闭、可注入凭据、严格脱敏和可测试的 contract-v1 HTTP 适配器。

**Architecture:** 保持既有领域端口不变，新增 `ProviderHttpTransport` 和 `ProviderCredentialResolver` 两个边界。适配器只做白名单 DTO 映射和严格结果解析；Spring 通过显式 `mode=http` 选择 HTTP 实现，默认继续使用 Mock，未配置或外部失败时 fail-closed。

**Tech Stack:** Java 21、Spring Boot 3.4、JDK `HttpClient`、Jackson、JUnit 5、AssertJ、现有 Maven 测试体系。

**Spec:** `docs/superpowers/specs/2026-08-25-provider-http-adapter-design.md`

## Global Constraints

- 默认配置必须继续使用 Mock，未显式启用 HTTP 时不得发生网络调用。
- 只允许 `secret://` 凭据引用；禁止在配置、日志、异常、审计和运行证据中出现凭据值。
- HTTP 请求只携带规格中列出的 ID、状态、耗时、哈希和受控环境字段。
- 不引入 OpenClaw、DeepEval 或 Langfuse SDK；核心域不依赖厂商类型。
- Adapter 不自行重试；复用现有 `ProviderRetryPolicy`。
- 真实 Provider 未提供 endpoint、Secret、网络和 UAT 证据时，平台 readiness 必须保持 `NOT_READY`。

---

### Task 1: Secure HTTP and Secret ports

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderHttpRequest.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderHttpResponse.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderHttpTransport.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/JavaHttpProviderTransport.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderCredentialResolver.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/quality/EnvironmentProviderCredentialResolver.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderUnavailableException.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/JavaHttpProviderTransportTest.java`

**Interfaces:**
- `ProviderHttpTransport.post(ProviderHttpRequest request)` returns `ProviderHttpResponse` and never logs body, headers, credential or response body.
- `ProviderCredentialResolver.resolve(String credentialRef)` returns an in-memory credential or throws a stable unavailable error.
- `ProviderHttpRequest` contains provider ID, endpoint, bearer credential, JSON body and timeout; its `toString()` must redact credential and body.

- [x] Write a failing test for successful status mapping, bearer header injection, timeout bounds and redacted request string.
- [x] Run `mvn -q -f apps/api/pom.xml -DforkCount=0 "-Dtest=JavaHttpProviderTransportTest" test`; observed the expected missing transport classes/methods compilation failure.
- [x] Implement the ports, environment resolver validation (`secret://env/<SAFE_ENV_NAME>`), JDK HTTP transport, timeout/error classification and stable exception mapping.
- [x] Run the focused transport test again; `JavaHttpProviderTransportTest` passed `4/4` without external network access.

### Task 2: HTTP adapter configuration and Runner mapping

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderAdapterConfig.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderAdapterConfiguration.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/OpenClawRunnerAdapter.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/OpenClawHttpAdapterTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/ProviderAdapterSelectionTest.java`

**Interfaces:**
- `ProviderAdapterConfig` preserves the existing three-argument constructor and adds a `mode` value with `contract` and `http` semantics.
- `OpenClawRunnerAdapter` receives `ProviderAdapterConfig`, `ProviderHttpTransport`, `ProviderCredentialResolver` and `ObjectMapper`; `execute` maps only the safe Runner request and validates the bounded response.

- [x] Write failing tests for `mode=http` selection, safe request allowlist, successful Runner result mapping, invalid response and missing Secret.
- [x] Run the focused adapter tests; observed the expected missing mode/constructor compilation failure.
- [x] Implement explicit mode selection, dependency injection, contract-v1 Runner JSON mapping and status/error conversion; keep contract mode fail-closed.
- [x] Run focused adapter and selection tests; Mock default, contract-only and HTTP paths passed.

### Task 3: Evaluation and Observability adapters

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/DeepEvalEvaluationAdapter.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/LangfuseObservabilityAdapter.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderAdapterConfiguration.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/quality/ExternalProviderHttpAdapterTest.java`

**Interfaces:**
- DeepEval sends case ID/name and safe Runner metadata, then validates `passed`, bounded `score` (0–100) and bounded non-sensitive reason.
- Langfuse sends only `RunnerExecutionSummary` metadata and accepts a status-only acknowledgement; it never sends evaluation case content.

- [x] Write failing tests for Evaluation success/invalid response, Observability safe payload, HTTP 429/5xx conversion and body redaction.
- [x] Run the focused external adapter tests; observed the expected missing HTTP constructor compilation failure.
- [x] Implement strict JSON DTO mapping and stable upstream errors without adding provider-specific SDKs or retries.
- [x] Run all external adapter focused tests; Evaluation/Observability HTTP tests passed `3/3`.

### Task 4: Regression, readiness boundary and documentation

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/quality/ProviderConnectivityProbeService.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/operations/PlatformReadinessService.java` only if the new mode status requires a boundary-preserving assertion.
- Modify: `docs/superpowers/specs/2026-08-12-provider-adapter-contracts.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `.superpowers/sdd/2026-08-25-skill-lifecycle-relational-projection/progress.md`
- Test: existing Provider readiness, API error, quality evaluation, runtime operations and lifecycle verifier suites.

**Interfaces:**
- Connectivity probe remains status-only and never returns endpoint, credential or response body.
- Platform readiness remains blocked until external evidence is accepted; selecting HTTP mode alone cannot produce production readiness.

- [x] Write/update failing regression assertions for HTTP mode, readiness NOT_READY boundary, status-only probe and no sensitive text in API errors.
- [x] Run focused API/provider/readiness tests and record the expected red result before implementation changes.
- [x] Implement only the minimal probe/readiness and documentation updates required by the new mode; no external acceptance claim was broadened.
- [x] Run `mvn -q -f apps/api/pom.xml -DforkCount=0 test`, parse Surefire counts, run `npm test`, `npm run build`, lifecycle verifier and `git diff --check`.
- [x] Record exact verification counts, Docker/network capability limitations and the still-open production acceptance items.
