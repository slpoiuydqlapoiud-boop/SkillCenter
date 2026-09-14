# JWKS Actor Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 JWT 身份边界增加受控 JWKS 公钥发现、缓存与密钥轮换能力。

**Architecture:** 保留现有 `JwtActorTokenVerifier` 的 claims、角色和错误语义，新增 `JwksKeySetProvider` 负责服务端配置 URL、JDK HttpClient、响应上限、TTL 缓存和单次刷新；`JwksActorTokenVerifier` 仅按 JWT `kid` 取得 RSA 公钥并完成 RS256 验签。`ActorResolver` 依据互斥配置选择静态 PEM 或 JWKS，绝不回退 local。

**Tech Stack:** Java 21、Spring Boot 3、JDK `HttpClient`、Jackson、JUnit 5、JDK `HttpServer`。

**Spec:** `docs/superpowers/specs/2026-08-25-jwks-actor-authentication-design.md`

## Global Constraints

- JWT 生产模式必须显式配置 `public-key` 或 `jwks-uri`，不能静默回退 local。
- 只接受 `RS256` 和 RSA 签名 key；不接受 `none`、HMAC 或客户端指定算法。
- JWKS URL 只来自配置；生产必须 HTTPS，测试只允许 loopback HTTP。
- timeout、TTL、响应体大小必须有界；过期缓存不得在 refresh 失败后续期。
- 不记录或返回 Token、JWKS 正文、密钥、URL、凭据和上游异常。
- 每个生产行为先 RED 后 GREEN；保留现有静态 PEM 与 local 回归。

### Task 1: Freeze configuration selection contracts

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ActorAuthenticationProperties.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ActorResolver.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/ActorAuthenticationPropertiesTest.java`

- [x] Write a failing test for mutually exclusive PEM/JWKS configuration, bounded network values, and JWT startup rejection when both are absent.
- [x] Run `mvn.cmd -q -f apps/api/pom.xml -DforkCount=0 -Dtest=ActorAuthenticationPropertiesTest test`; expected compilation/assertion failure was observed before implementation.
- [x] Add `jwksUri`, `connectTimeoutMs`, `requestTimeoutMs`, `cacheTtlSeconds`, and `maxResponseBytes` with exact bounds from the spec.
- [x] Make `ActorResolver` select exactly one verifier in JWT mode; invalid selection throws a stable startup configuration exception and never chooses local.
- [x] Re-run the focused test and confirm GREEN.

### Task 2: Implement bounded JWKS key provider

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/JwksKeySetProvider.java`
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/JwksKeySetSnapshot.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/JwksKeySetProviderTest.java`

- [x] Write failing tests using JDK `HttpServer` for valid RSA JWKS, response-size rejection, invalid JSON/key rejection, non-2xx, timeout, TTL expiration, and one refresh for an unknown `kid`.
- [x] Run the focused test and confirm failure was caused by missing provider contracts, not fixture setup.
- [x] Implement URI validation, bounded `HttpClient` requests, content-length/stream response limit, strict JSON allowlist, RSA `n/e` parsing, immutable snapshots and TTL checks.
- [x] Implement synchronized refresh coalescing and a bounded one-second retry backoff so concurrent/repeated misses do not create unbounded requests; failed refresh must not extend expired entries.
- [x] Re-run provider tests and confirm GREEN.

### Task 3: Add JWKS JWT verifier and Spring wiring

**Files:**
- Create: `apps/api/src/main/java/com/huawei/skillcenter/governance/JwksActorTokenVerifier.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/JwtActorTokenVerifier.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ActorResolver.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/JwksActorTokenVerifierTest.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/ActorResolverJwksConfigurationTest.java`

- [x] Write failing tests for valid `kid`, rotated `kid`, missing/unknown `kid`, algorithm confusion, expired claims, and redacted invalid-token errors.
- [x] Run the focused verifier/configuration tests and confirm RED.
- [x] Implement verifier behavior by reusing the existing claims/role contract and calling provider refresh at most once per verification miss.
- [x] Wire the Spring constructor to choose static or JWKS verifier and preserve existing constructor seams used by tests.
- [x] Re-run JWKS and existing JWT tests and confirm GREEN.

### Task 4: Configuration, documentation, and full verification

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `docs/superpowers/specs/2026-08-25-jwt-actor-authentication-boundary-design.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`
- Modify: `docs/superpowers/plans/2026-08-25-jwt-actor-authentication-boundary.md`

- [x] Add environment-backed JWKS configuration with safe development defaults and no credentials.
- [x] Document production HTTPS, network allowlist, cache/rotation procedure, failure behavior, and the boundary between local stub tests and real SSO acceptance.
- [x] Mark only the platform-side JWKS adapter complete; keep real SSO endpoint, organization claims, key governance, revocation, network, and UAT open.
- [x] Run focused governance tests, full API regression (`785/785`), Web tests/build (`149/149`), lifecycle verifier (`6/6`), and `git diff --check`.
