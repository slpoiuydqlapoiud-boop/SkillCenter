# SkillCenter SSO / JWT / JWKS / 组织目录 生产接入指南

> **证据 ID**：`SSO_ORGANIZATION`
> **适配层**：`apps/api/src/main/java/com/huawei/skillcenter/governance`
> **设计文档**：[`docs/superpowers/specs/2026-08-25-jwks-actor-authentication-design.md`](../../docs/superpowers/specs/2026-08-25-jwks-actor-authentication-design.md)、[`jwt-actor-authentication-boundary-design.md`](../../docs/superpowers/specs/2026-08-25-jwt-actor-authentication-boundary-design.md)、[`organization-directory-snapshot-design.md`](../../docs/superpowers/specs/2026-08-25-organization-directory-snapshot-design.md)
> **自检清单**：见文末 [§8 SSO_ORGANIZATION 证据 ID 自检清单](#8-sso_organization-证据-id-自检清单)

本指南面向**生产环境把企业 SSO 真实接通**的运维与安全工程师。平台侧的 JWKS 适配、JWT 验证、组织目录 HTTP 适配均已 100% 实现并通过单元/集成测试（`apps/api/src/test/java/.../quality/Jwks*Test.java`、`.../governance/OrganizationDirectory*Test.java`），本指南只覆盖**接入真实 IdP 时的配置、验证、应急流程**。

---

## 1. 总览与适用边界

### 1.1 适配层能力

| 组件 | 路径 | 能力 | 校验位置 |
|---|---|---|---|
| `ActorAuthenticationProperties` | `governance/ActorAuthenticationProperties.java` | 绑定 `mode=local\|jwt`、JWT/JWKS 配置 | 应用启动 |
| `JwtActorTokenVerifier` | `governance/JwtActorTokenVerifier.java` | RS256 本地公钥验签 | 每个 API 请求 |
| `JwksActorTokenVerifier` | `governance/JwksActorTokenVerifier.java` | 按 `kid` 选择 JWKS key 并验签 | 每个 API 请求 |
| `JwksKeySetProvider` | `governance/JwksKeySetProvider.java` | 有界 JWKS 拉取、缓存、刷新去抖 | 同上 |
| `OrganizationDirectoryProperties` | `governance/OrganizationDirectoryProperties.java` | 组织目录配置 + `@PostConstruct` 启动校验 | 应用启动 |
| `HttpOrganizationDirectoryClient` | `governance/HttpOrganizationDirectoryClient.java` | 拉取 `organization-directory.v1` 快照，allowlist 解析 | 管理员 `/sync` 调用 |
| `OrganizationDirectorySyncService` | `governance/OrganizationDirectorySyncService.java` | 快照原子替换 + revision 防回退 | 同上 |
| `OrganizationDirectoryController` | `governance/OrganizationDirectoryController.java` | `GET /api/v1/admin/governance/organization-directory`、`POST /sync` | 管理员 |

### 1.2 不在本指南范围（明确不做）

- ❌ OIDC 授权码登录回调、Cookie 会话、Token 刷新端点
- ❌ 客户端登录页（Web 走独立 SSO 流程，不属于 API 网关）
- ❌ 实时 Token 撤销列表（JWKS 轮转即视为撤销；fail-closed 行为在 §5）
- ❌ 用户画像同步（组织目录只同步 TEAM 授权所需的 `teamId/name/status/memberUserIds`）
- ❌ 组织目录自动推送（管理员显式触发 `/api/v1/admin/governance/organization-directory/sync`）

---

## 2. SSO IdP 选择与契约对齐

### 2.1 必须满足的 IdP 契约

| 项 | 要求 | 不满足后果 |
|---|---|---|
| 签名算法 | **仅 RS256** | 任何非 RS256 的 Token → 401（`Invalid bearer token`） |
| 公钥 RSA 长度 | **≥ 2048 bit**（`n.bitLength() >= 2048`） | 弱 RSA key → 401 |
| JWKS 端点 | **HTTPS**（loopback 测试例外）；不跟随 redirect | `JWKS_URI_INVALID` |
| JWKS 字段 | 根只允许 `keys`；每个 key 只允许 `kty / use / alg / kid / n / e` | 任何多余字段 → `DIRECTORY_INVALID_RESPONSE` 风格的拒绝（解析为 null） |
| Token 必要声明 | `sub`（文本，≤128 字符）、`exp`（数字，未来）、`iss`（== 配置）、`aud`（含配置） | 缺/错 → 401 |
| 角色 | `roles[]` 或 `role`（白名单：`developer / admin / viewer / maintainer / reviewer`） | 非白名单 → 401 |
| 团队 claim | 文本或数组，每项匹配 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`，最多 100 项 | 非法 → 401 |
| 时钟偏移 | 平台容忍 ≤ 60s（默认）；`exp / nbf` 校验含 skew | 边缘情况 → 401 |

### 2.2 IdP 自检清单（首次接入前由安全工程师确认）

- [ ] IdP 签发的 Access Token 使用 RS256（不接受 HS256/ES256/PS256 等）
- [ ] IdP JWKS 端点为 HTTPS 且支持标准 `application/jwk-set+json`
- [ ] JWKS 中至少包含 1 把 `kty=RSA`、`alg=RS256`、`use=sig`、`kid` 非空、`n`/`e` 有效的 RSA-2048+ 公钥
- [ ] IdP 在 Token 中下发的 `iss` 与 `aud` 与平台配置一致
- [ ] IdP 在 Token 中下发的角色名收敛到 `developer / admin / viewer / maintainer / reviewer` 之一（多余前缀 `role_` 会自动剥离）
- [ ] IdP 团队 claim 字段名与平台 `SKILL_CENTER_JWT_TEAM_CLAIM` 一致（如 `teams` / `groups` / `custom_team`）
- [ ] IdP 团队 ID 符合 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`（如 IdP 用 GUID/UUID 需先映射为短 ID）

### 2.3 推荐 IdP（已通过平台契约验证）

| IdP | RS256 | JWKS | 团队 claim | 备注 |
|---|---|---|---|---|
| Azure Entra ID (Azure AD) | ✅ | ✅ | `groups` 或 `custom_team` | 需在应用注册中开启 `groups` claim |
| Okta | ✅ | ✅ | `groups` | 注意 group 数量上限 |
| Keycloak | ✅ | ✅ | `groups` 或自定义 mapper | 需配置 RS256 签名（非 RS384/RS512） |
| 自建 IdP | ⚠️ 需审计 | ⚠️ 需审计 | 自定义 | **必须**经安全团队评审 |

---

## 3. 组织目录契约（`organization-directory.v1`）

### 3.1 必填响应体（字段 allowlist 严格）

```json
{
  "schemaVersion": "organization-directory.v1",
  "source": "corp-directory",
  "revision": "2026-09-08T06:00:00Z",
  "fetchedAt": "2026-09-08T06:00:01Z",
  "teams": [
    {
      "teamId": "team-a",
      "name": "AI Platform",
      "status": "active",
      "memberUserIds": ["alice", "bob"]
    }
  ]
}
```

### 3.2 字段约束（违反任一项 → `DIRECTORY_INVALID_RESPONSE`）

| 字段 | 必填 | 约束 |
|---|---|---|
| `schemaVersion` | ✅ | 必须字符串 `"organization-directory.v1"` |
| `source` | ✅ | `[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}` |
| `revision` | ✅ | 同 `source` 模式；用于防回退 |
| `fetchedAt` | ✅ | ISO-8601 Instant（`2026-09-08T06:00:01Z`） |
| `teams[]` | ✅ | 数组，最多 5000 |
| `teams[].teamId` | ✅ | `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` |
| `teams[].name` | ✅ | 字符串，1-256 字符 |
| `teams[].status` | ✅ | `[A-Za-z][A-Za-z0-9_-]{0,31}`，自动小写（推荐 `active / inactive`） |
| `teams[].memberUserIds[]` | ✅ | 字符串数组，单团队最多 10000，全局最多 200000，每个 `[A-Za-z0-9][A-Za-z0-9._@:+-]{0,127}` |

### 3.3 错误码映射（HTTP 适配器抛出）

| 错误码 | 含义 | 处置 |
|---|---|---|
| `DIRECTORY_NOT_CONFIGURED` | `mode != http` | 平台配置错；不应发生 |
| `DIRECTORY_CREDENTIAL_UNAVAILABLE` | Secret Manager 解析失败 | 检查 `SKILL_CENTER_ORGANIZATION_DIRECTORY_CREDENTIAL_REF` |
| `DIRECTORY_HTTP_STATUS` | 目录服务返回非 200 | 联动 IdP/目录服务 |
| `DIRECTORY_TIMEOUT` | 请求超时 | 调高 `REQUEST_TIMEOUT_MS` 或排查网络 |
| `DIRECTORY_INTERRUPTED` | 线程被中断 | 排查上游限流 |
| `DIRECTORY_UNREACHABLE` | DNS/TLS/连接错 | 检查网络白名单、TLS 证书 |
| `DIRECTORY_RESPONSE_TOO_LARGE` | 响应超过 `MAX_RESPONSE_BYTES` | 调大或拆分团队 |
| `DIRECTORY_INVALID_RESPONSE` | schema/字段/数量/ID 非法 | 修复目录服务输出 |
| `DIRECTORY_STALE` | 快照超过 `MAX_AGE_SECONDS` | 触发 `/sync` 重拉 |
| `DIRECTORY_REVISION_REGRESSION` | 新 revision < 当前 revision | 目录服务回退，平台拒绝覆盖 |

---

## 4. 配置矩阵（变量 → 文件 → 默认值）

所有变量已在 `deploy/prod/.env.prod.template` 第 9/10 节标注，本节说明**取值依据**。

### 4.1 SSO / JWT / JWKS（第 9 节）

| 变量 | 必填 | 合法范围 | 推荐生产取值 | 取值依据 |
|---|---|---|---|---|
| `SKILL_CENTER_AUTHENTICATION_MODE` | ✅ | `local \| jwt` | `jwt` | ProductionConfigCheck 强制 `jwt` |
| `SKILL_CENTER_JWT_JWKS_URI` | ✅* | HTTPS URL | 企业 SSO JWKS | *与 `PUBLIC_KEY` 二选一 |
| `SKILL_CENTER_JWT_PUBLIC_KEY` | ✅* | PEM | 通常留空 | 静态 PEM 兜底（仅紧急回退） |
| `SKILL_CENTER_JWT_ISSUER` | ✅ | URL 字符串 | IdP issuer | 与 IdP Token 中 `iss` 一致 |
| `SKILL_CENTER_JWT_AUDIENCE` | ✅ | 字符串 | `skillcenter-api` | 与 IdP 应用注册 `aud` 一致 |
| `SKILL_CENTER_JWT_TEAM_CLAIM` | 推荐 | `[A-Za-z][A-Za-z0-9_.:-]{0,127}` | `teams` | 与 IdP Token claim 名一致 |
| `SKILL_CENTER_JWT_TEAM_CLAIM_REQUIRED` | 推荐 | boolean | `true` | 防止默认空团队导致越权 |
| `SKILL_CENTER_JWT_CLOCK_SKEW_SECONDS` | 可选 | 0-300 | `60` | 网络 NTP 偏差容忍 |
| `SKILL_CENTER_JWT_CONNECT_TIMEOUT_MS` | 可选 | 100-10000 | `1500` | 同集群低延迟；跨区可调高 |
| `SKILL_CENTER_JWT_REQUEST_TIMEOUT_MS` | 可选 | 100-15000 | `3000` | 同上 |
| `SKILL_CENTER_JWT_CACHE_TTL_SECONDS` | 可选 | 1-86400 | `300` | 小于 IdP 密钥轮转间隔 |
| `SKILL_CENTER_JWT_MAX_RESPONSE_BYTES` | 可选 | 4096-1048576 | `262144` | 128 个 key × 2KB 余量 |

### 4.2 组织目录（第 10 节）

| 变量 | 必填 | 合法范围 | 推荐生产取值 |
|---|---|---|---|
| `SKILL_CENTER_ORGANIZATION_DIRECTORY_MODE` | ✅ | `local \| http` | `http` |
| `SKILL_CENTER_ORGANIZATION_DIRECTORY_ENDPOINT` | ✅* | HTTPS URL | 企业目录服务 URL |
| `SKILL_CENTER_ORGANIZATION_DIRECTORY_CREDENTIAL_REF` | ✅* | `secret://` 引用 | `secret://skillcenter/prod/org-directory/token` |
| `SKILL_CENTER_ORGANIZATION_DIRECTORY_CONNECT_TIMEOUT_MS` | 可选 | 100-10000 | `1500` |
| `SKILL_CENTER_ORGANIZATION_DIRECTORY_REQUEST_TIMEOUT_MS` | 可选 | 100-15000 | `3000` |
| `SKILL_CENTER_ORGANIZATION_DIRECTORY_MAX_RESPONSE_BYTES` | 可选 | 4096-1048576 | `1048576` |
| `SKILL_CENTER_ORGANIZATION_DIRECTORY_MAX_AGE_SECONDS` | 可选 | 30-86400 | `900`（15 分钟） |

### 4.3 凭据注入（Secret Manager 路径）

- `SKILL_CENTER_ORGANIZATION_DIRECTORY_CREDENTIAL_REF`：组织目录 Bearer token
- K8s 推荐：`ExternalSecret` → `secretKeyRef` → `env.valueFrom.secretKeyRef`
- Docker Compose：`secrets:` + `environment:` 引用
- 严禁将 Bearer token 写入 `.env.prod.*` 或 git 历史

详见 [`deploy/prod/secret-references.md`](./secret-references.md) 第 2 节。

---

## 5. 密钥轮转、撤销与应急

### 5.1 正常轮转（IdP 周期性轮换 RS256 密钥）

```
T0   IdP 公布新 kid 到 JWKS（同时下发新 Token）
     ↓
T0+1 平台下次请求：JwksKeySetProvider 缓存命中失败 → 触发单次 JWKS refresh
     ↓
T0+2 新 kid 命中 → 新 RSA 公钥验证新 Token 通过
     ↓
T0+N 旧 kid 在缓存 TTL（默认 300s）后从进程内缓存淘汰
```

**关键属性**：
- 单次未知 `kid` 在 1 秒退避窗口内不重复拉 JWKS（防 DoS）
- 旧 key 在 TTL 后被淘汰，**不会**延长有效期
- 缓存解析失败映射为 `Invalid bearer token`，无明文回显

### 5.2 应急撤销（IdP 主动踢人）

- **场景**：员工离职、Token 泄露、SSO 异常会话
- **方式**：
  1. **首选**：从 IdP 撤销该用户的会话/Token（最快生效）
  2. **次选**：管理员触发 `POST /api/v1/admin/governance/organization-directory/sync` 强制重拉组织目录（移除该用户的所有团队 ID）
  3. **紧急**：将 `SKILL_CENTER_AUTHENTICATION_MODE` 切回 `local`（仅作灾备，不推荐常规使用）
- **响应**：旧 Token 在 `exp` 到期前仍有效，但任何要求 TEAM 范围的接口会因 `team-claim` 不在最新快照而拒绝

### 5.3 灾难场景（IdP 不可用）

| 现象 | 自动行为 | 处置 |
|---|---|---|
| JWKS 拉取失败 | `find(kid)` 返回 `Optional.empty()` → 401 | 平台 fail-closed，不会本地兜底 |
| JWKS 拉取超时 | 同上 | 调高 `REQUEST_TIMEOUT_MS` 或排查网络 |
| 组织目录 `/sync` 失败 | `OrganizationDirectoryUnavailableException` | 维持旧快照，标 `status=degraded` |
| 快照超期 | 标 `DIRECTORY_STALE`；TEAM 授权使用旧快照 | 触发 `/sync` 重拉 |

> ⚠️ **平台无回退到本地身份**：JWKS 不可用意味着"全员 401"，这是 fail-closed 设计（设计文档 §不做事项）。

---

## 6. 网络与白名单

| 出口 | 协议 | 端口 | 凭据 | 备注 |
|---|---|---|---|---|
| `JWKS_URI` | HTTPS | 443 | 无 | 不跟随 redirect |
| `ORG_DIRECTORY_ENDPOINT` | HTTPS | 443 | Bearer | 不跟随 redirect |

**网络白名单**（平台层维护，本文件不存）：
- 出向：API 节点 → 企业 SSO JWKS 端点 → 企业组织目录端点
- 证书：由平台 cert-manager 管理；企业内部 CA 需提前注入 truststore

---

## 7. 上线前 9 步 Checklist

| # | 步骤 | 责任方 | 证据 |
|---|---|---|---|
| 1 | IdP 满足 §2.1 契约（自检表已勾完） | 安全 + 平台 | IdP 配置截图 + 平台启动日志 |
| 2 | 企业组织目录返回 §3.1 schema（用 curl 验） | 运维 | curl 截图 |
| 3 | `deploy/prod/.env.prod.cluster-a` 复制并按 §4 替换占位 | 运维 | git diff（仅模板）+ 红线检查 |
| 4 | Secret Manager 注入 `skillcenter/prod/org-directory/token` | 安全 + 运维 | ESO 同步状态 |
| 5 | K8s NetworkPolicy / 防火墙放行 §6 出口 | 网络 | 策略 diff |
| 6 | `pwsh scripts/verify-production-config.ps1 -Json` 全部 READY | 运维 | JSON 输出 |
| 7 | `pwsh scripts/verify-production-handoff.ps1 -BaseUrl https://api -Json` 全部 READY | 运维 | JSON 输出 |
| 8 | UAT：用真实 IdP 账号登录 → 调一个 admin API → 验证 200/403 | QA + 业务方 | UAT 报告 |
| 9 | 写入 `docs/operations/sso-runbook.md`（含 IdP 联系人、轮转计划） | 平台 + 运维 | 文档链接 |

---

## 8. SSO_ORGANIZATION 证据 ID 自检清单

> 每条对应 ProductionConfigCheck 或运行时验证点；上线 UAT 时逐项打勾。

| # | 验证项 | 验证方式 | 通过判据 |
|---|---|---|---|
| 1 | `SKILL_CENTER_AUTHENTICATION_MODE == jwt` | `verify-production-config.ps1` | `authentication.mode` READY |
| 2 | `SKILL_CENTER_JWT_JWKS_URI` 为 HTTPS | 同上 | `authentication.jwks-uri` READY |
| 3 | `SKILL_CENTER_JWT_ISSUER` 与 IdP Token `iss` 一致 | IdP 配置 diff + 启动日志 | `authentication.issuer` READY |
| 4 | `SKILL_CENTER_JWT_AUDIENCE` 与 IdP Token `aud` 一致 | 同上 | `authentication.audience` READY |
| 5 | `SKILL_CENTER_ORGANIZATION_DIRECTORY_MODE == http` | `verify-production-config.ps1` | `organization-directory.mode` READY |
| 6 | `SKILL_CENTER_ORGANIZATION_DIRECTORY_ENDPOINT` 为 HTTPS | 同上 | `organization-directory.endpoint` READY |
| 7 | `SKILL_CENTER_ORGANIZATION_DIRECTORY_CREDENTIAL_REF` 为 `secret://` | 同上 | `organization-directory.credential-ref` READY |
| 8 | IdP Token 签名算法 = RS256 | `curl <JWKS_URI>` + 启动日志（`alg=RS256` 解析） | 启动无 IllegalArgumentException |
| 9 | JWKS 中 RSA key ≥ 2048 bit | `JwksKeySetProvider.parse` 日志 | 同上 |
| 10 | Token 角色收敛到白名单 | 单元测试 `JwtActorTokenVerifierTest` + UAT | 5 个角色都能用 |
| 11 | 团队 claim 必填（`TEAM_CLAIM_REQUIRED=true`） | 缺 claim 测试请求 → 401 | UAT 用例 |
| 12 | `/api/v1/admin/governance/organization-directory/sync` 返回 200 | curl + admin Token | `status=active` |
| 13 | 同步快照原子替换（旧 revision 不被覆盖） | 单元测试 `OrganizationDirectorySyncServiceTest` + 集成测试 | 旧快照保留 |
| 14 | 快照 schema 校验 | 单元测试 `HttpOrganizationDirectoryClientTest` | 未知字段 → `DIRECTORY_INVALID_RESPONSE` |
| 15 | 快照超期标记 | 设 `MAX_AGE_SECONDS=1` 后 sleep 2s → 调需 TEAM 接口 | 401 或拒绝 |
| 16 | JWKS 不可达 → 401（不降级） | 断网后 curl API | 持续 401，无本地兜底 |
| 17 | IdP 撤销后旧 Token 失败 | IdP 撤销 + 用旧 Token 调 API | 401 |
| 18 | 密钥轮转不需重启 | IdP 换 key 后调 API（无重启） | 200 |
| 19 | SSO 审计日志（每次验签成功/失败带 requestId） | 日志平台检索 | 含 `actor=`, `subject=`, `result=allow\|deny` |
| 20 | 组织目录同步审计日志（admin actor + revision） | 日志平台检索 | 含 `actor=admin, action=directory.sync, revision=` |

> **证据归档**：上线后把上述每条验证项的截图/JSON 输出归档到 `docs/operations/sso-uat-evidence-YYYYMMDD/`，命名 `SSO_ORGANIZATION-{01..20}.md`。

---

## 9. 故障排查速查

| 现象 | 根因 | 排查路径 |
|---|---|---|
| 所有 API 返回 401 | JWKS 拉取失败 | 看启动日志 `JWKS_HTTP_STATUS` / `JWKS_TIMEOUT` |
| 单用户 401 | Token `kid` 不在 JWKS | IdP 是否轮换了 key 且 JWKS 已更新 |
| 单团队 API 403 | 用户不在最新 Org Directory 团队 | 触发 `/sync` + IdP 检查团队归属 |
| `/sync` 返回 DIRECTORY_INVALID_RESPONSE | 目录服务多了字段/改了 schema | curl 对比 §3.1 契约 |
| `/sync` 返回 DIRECTORY_CREDENTIAL_UNAVAILABLE | Secret 路径错或 ESO 同步失败 | `kubectl get externalsecret` |
| 启动失败 "JWT public key must use RSA" | 同时配了 `jwks-uri` 和 `public-key` | 二选一，清空另一个 |
| 启动失败 "JWKS URI must use HTTPS" | `jwks-uri` 用 HTTP | 改 HTTPS；loopback 测试例外 |

---

**版本**：v1.0（2026-09-08）  
**责任人**：平台架构组 + 安全团队  
**变更历史**：首版（SSO/JWT/JWKS/Org 适配器生产接入指南）