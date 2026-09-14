# JWT Actor Authentication Boundary Design

日期：2026-08-25  
状态：平台侧适配已实现；目标环境 SSO/JWKS 验收仍开放

## 目标

让生命周期控制面可以从本地开发身份切换到受签名保护的企业身份，同时保持本地测试与离线开发可用。JWT 模式必须消除客户端通过 `X-User-Id`/`X-User-Role` 伪造身份和角色的路径。

## 认证模式

- `local`：仅用于本地开发和兼容现有回归，继续读取受控的本地 Header 默认身份。
- `jwt`：只接受 `Authorization: Bearer <token>`；即使同时提供身份 Header，也完全忽略 Header。

JWT 模式使用 RS256、RSA 公钥、文本 `sub`、必需数字 `exp`，并可校验 `iss`、`aud`、`nbf`。角色从 `roles` 数组或 `role` 字符串读取，只允许平台白名单角色；所有失败统一为 `FORBIDDEN`/`Invalid bearer token`，不回显 token、声明或签名错误。

## 配置边界

```yaml
skill-center:
  security:
    authentication:
      mode: jwt
      jwt:
        public-key: ${SKILL_CENTER_JWT_PUBLIC_KEY:}
        jwks-uri: ${SKILL_CENTER_JWT_JWKS_URI:}
        issuer: ${SKILL_CENTER_JWT_ISSUER:}
        audience: ${SKILL_CENTER_JWT_AUDIENCE:skill-center}
        team-claim: ${SKILL_CENTER_JWT_TEAM_CLAIM:teams}
        team-claim-required: false
        clock-skew-seconds: 30
        connect-timeout-ms: 1000
        request-timeout-ms: 2000
        cache-ttl-seconds: 300
        max-response-bytes: 262144
```

JWT 模式必须且只能配置 `public-key` 或 `jwks-uri`，缺失或同时配置时启动失败，不回退到本地身份。平台侧现已提供受控 JWKS resolver：只接受配置的 HTTPS（本地 loopback 测试允许 HTTP）、RS256/RSA、`kid`、有界响应、超时和 TTL 缓存；未知 `kid` 只触发一次刷新，过期缓存刷新失败时 fail-closed。`team-claim` 由服务端配置，缺失的可选 claim 也会形成“空且权威”的团队集合，避免回退本地成员表；只有本地 active TeamDefinition 且 claim 命中时才可见，维护操作仍要求本地 maintainer binding。真实企业 SSO 端点、网络白名单、Secret Manager、组织目录字段约定、撤销策略和 UAT 仍需目标环境接管，不能把本地 JWKS stub 测试视为 SSO 生产验收。

## 验收证据

- `JwtActorTokenVerifierTest`：RS256、签名、过期、算法混淆、角色、issuer/audience 边界。
- `ActorResolverAuthenticationTest`：JWT 模式拒绝 Header fallback。
- `ActorResolverJwtConfigurationTest`：真实 Spring 上下文切换 JWT，Skill API 忽略伪造角色 Header。
- `JwksKeySetProviderTest` / `JwksActorTokenVerifierTest`：JWKS key 解析、`kid` 轮换、TTL、超时/超大响应和错误脱敏。
- `ActorResolverJwksConfigurationTest`：无静态 PEM 时通过服务端 JWKS 配置解析 Bearer token。
- `ActorOrganizationClaimsContractTest` / `JwtOrganizationClaimsVerifierTest`：团队声明边界、权威空集合、拒绝非法/重复/超量 claim。
- `SkillAuthorizationServiceTest`：声明团队可见性、本地兼容、非活动团队拒绝和 maintainer binding 保留。
- `SecurityBoundarySmokeTest` 与 `RoleGuardTest`：本地模式兼容且安全边界未回归。
