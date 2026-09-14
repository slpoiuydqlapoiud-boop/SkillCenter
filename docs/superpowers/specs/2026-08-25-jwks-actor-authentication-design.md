# JWKS Actor Authentication Design

状态：平台侧实现规格；真实企业 SSO 端点、网络白名单、密钥管理和 UAT 仍需目标环境验收。

## 目标

在现有 RS256 JWT 身份边界上增加受控 JWKS 公钥发现与轮换能力，使企业 SSO 可以在不重新构建或重启平台的情况下轮换签名密钥，同时保持现有 `ActorResolver`、角色授权、错误码和本地静态 PEM 兼容路径不变。

## 范围与不做事项

- 支持 JWKS 中的 RSA 公钥和 JWT `kid` 选择；只接受 `RS256`。
- 支持有界缓存、未知 `kid` 的单次刷新和缓存失效后的 fail-closed 行为。
- 允许开发环境使用静态 PEM；JWT 生产模式可显式选择 `public-key` 或 `jwks-uri`。
- 不实现 OIDC 登录回调、Cookie 会话、组织目录同步、团队 claim 映射或 Token 撤销列表。
- 不从 Token、请求参数或用户输入动态决定 JWKS URL；地址只来自服务端配置。

## 配置契约

```yaml
skill-center:
  security:
    authentication:
      mode: local # local | jwt
      jwt:
        public-key: ${SKILL_CENTER_JWT_PUBLIC_KEY:}
        jwks-uri: ${SKILL_CENTER_JWT_JWKS_URI:}
        issuer: ${SKILL_CENTER_JWT_ISSUER:}
        audience: ${SKILL_CENTER_JWT_AUDIENCE:skill-center}
        clock-skew-seconds: 30
        connect-timeout-ms: 1000
        request-timeout-ms: 2000
        cache-ttl-seconds: 300
        max-response-bytes: 262144
```

- `public-key` 与 `jwks-uri` 不能同时配置；两者都为空时 JWT 模式启动失败。
- `jwks-uri` 必须为绝对 HTTP(S) URL；生产配置必须为 HTTPS。测试只允许 loopback HTTP。
- timeout、TTL 和响应大小都有固定上下界，非法配置启动失败。
- JWKS 不保存到业务快照、审计或 API 响应；只存在进程内缓存。

## 数据流与安全边界

1. `ActorResolver` 从 Bearer Token 读取 JWT，保持现有长度、结构、`alg`、声明和角色校验。
2. JWKS verifier 先解析无信任的 header 只取得 `kid`，不使用其中的 URL 或算法覆盖服务端策略。
3. 缓存中存在匹配 `kid` 时直接验签；不存在时最多执行一次有界 JWKS refresh，再重试一次验签；同一未知 `kid` 在短退避窗口内不重复刷新。
4. JWKS 解析只接受 `kty=RSA`、`use=sig`（缺省允许）、`alg=RS256`（缺省允许）、非空且长度受限的 `kid`、合法 `n/e`。
5. 同一 `kid` 的新 key 替换旧 key；未知 `kid`、重复 `kid`、非法 key、超大响应和网络异常均拒绝 Token。
6. 已缓存 key 在 TTL 后不继续信任；刷新失败不会把过期 key 延长为有效。
7. 所有失败仍映射为 `Invalid bearer token`，不返回 Token、JWKS 正文、URL、密钥或上游异常。

## 组件边界

- `JwksKeySetProvider`：负责 URL 校验、HTTP 获取、大小/超时限制、JSON 解析、key cache 和 refresh 去重。
- `JwksActorTokenVerifier`：复用现有 JWT claims/role 语义，使用 provider 按 `kid` 取得 RSA 公钥并完成 RS256 验签。
- `ActorAuthenticationProperties.JwtProperties`：增加 JWKS 与有界网络/缓存配置。
- `ActorResolver`：只负责根据配置选择静态 verifier 或 JWKS verifier，不改变请求入口。

## 验收标准

- 有效 `kid`、有效签名和有效 claims 返回正确 Actor。
- key rotation：首次使用新 `kid` 触发一次刷新并成功；旧缓存过期且刷新失败时拒绝。
- 缺失/重复/未知 `kid`、算法混淆、非法 RSA 参数、错误签名和错误 claims 均拒绝。
- HTTP 超时、非 2xx、超大响应、非法 JSON 和敏感上游响应均 fail-closed 且不泄露正文。
- 并发或重复请求不会为同一过期/未知 `kid` 产生无界刷新风暴。
- 静态 PEM 模式、local 模式和现有 Spring JWT 配置回归保持通过。
- 文档明确：本地 JWKS stub 测试不代表真实 SSO、网络、Secret Manager、JWKS 轮换策略和 UAT 已完成。
