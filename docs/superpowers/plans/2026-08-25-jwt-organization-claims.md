# JWT Organization Claims Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将签名 JWT 的团队声明安全映射到 Skill TEAM 范围授权。

**Architecture:** `Actor` 增加兼容的不可变团队元数据；静态 PEM 与 JWKS verifier 共同调用 bounded claim parser。`SkillAuthorizationService` 只在本地 active Team 存在时消费权威 claim，不改变 admin、maintainer binding 或其他范围语义。

**Tech Stack:** Java 21、Spring Boot 3、Jackson、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-08-25-jwt-organization-claims-design.md`

## Global Constraints

- 保留 `new Actor(userId, role)`，local 模式行为不变。
- 只接受有界字符串团队 ID；不保存、返回或审计原始 claims。
- JWT claim 缺失/非法/超量/未知团队必须对 TEAM 范围 fail-closed。
- 生产行为先 RED 后 GREEN；静态 PEM、JWKS 和既有授权回归必须保持通过。

### Task 1: Actor and claim configuration contract

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/Actor.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/ActorAuthenticationProperties.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/ActorOrganizationClaimsContractTest.java`

- [x] Write failing tests for two-argument Actor compatibility, immutable/deduplicated team IDs, bounded claim name, and required flag.
- [x] Run the focused test and observe missing fields/validation failure.
- [x] Add `teamIds`, `teamClaimsAuthoritative`, `teamClaim`, and `teamClaimRequired` with safe bounds.
- [x] Re-run focused contract tests and confirm GREEN.

### Task 2: Shared JWT claim extraction

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/JwtActorTokenVerifier.java`
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/governance/JwksActorTokenVerifier.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/governance/JwtOrganizationClaimsVerifierTest.java`

- [x] Write failing tests for text claim, text-array claim, missing optional/required claim, malformed values, duplicate IDs and over-100 IDs.
- [x] Run the focused verifier test and confirm RED.
- [x] Implement one shared bounded parser path used by both PEM and JWKS verification; preserve existing token error redaction.
- [x] Add claim configuration to `ActorResolver` selection without allowing request-driven claim names.
- [x] Re-run PEM/JWKS verifier and existing JWT tests.

### Task 3: TEAM scope authorization integration

**Files:**
- Modify: `apps/api/src/main/java/com/huawei/skillcenter/access/SkillAuthorizationService.java`
- Test: `apps/api/src/test/java/com/huawei/skillcenter/access/SkillAuthorizationOrganizationClaimTest.java`

- [x] Write failing tests for claim-authorized active team, absent claim denial, inactive/unknown team denial, local Actor compatibility, and maintainer binding preservation.
- [x] Run the focused authorization test and confirm RED.
- [x] Implement active Team existence plus authoritative claim membership; keep local Actor membership path unchanged.
- [x] Confirm team IDs never enter controller response or audit projections.
- [x] Re-run focused authorization and lifecycle relation tests.

### Task 4: Documentation and full verification

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `docs/superpowers/specs/2026-08-25-jwt-actor-authentication-boundary-design.md`
- Modify: `docs/project/remaining-coding-tasks-status.md`
- Modify: `docs/project/M11-external-integration-runbook.md`

- [x] Add environment-backed team claim configuration with no sensitive defaults.
- [x] Document claim contract, fail-closed behavior, local compatibility, and real organization-directory acceptance boundary.
- [x] Run API full regression, Web tests/build, lifecycle verifier, and `git diff --check`.
