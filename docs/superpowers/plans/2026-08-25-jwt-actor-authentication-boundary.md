# JWT Actor Authentication Boundary Implementation Plan

## Tasks

- [x] 冻结 local/jwt 模式、RS256、公钥、声明和角色白名单契约。
- [x] 先写测试覆盖签名、过期、算法混淆、issuer/audience、Header fallback 和错误脱敏。
- [x] 实现 `JwtActorTokenVerifier` 与 `ActorTokenVerifier`，不保存或回显 token。
- [x] 增加 `ActorAuthenticationProperties` 配置绑定；JWT 模式缺公钥时 fail-closed。
- [x] 接入 `ActorResolver`：JWT 模式只信任 Bearer token，local 模式保持开发兼容。
- [x] 运行单元、Spring JWT 上下文和既有安全冒烟测试。
- [x] 平台侧增加受控 JWKS resolver：服务端 URL、RS256/RSA/`kid` allowlist、有界 HTTP、TTL、未知 key 单次刷新和过期 fail-closed。
- [x] 平台侧增加静态 PEM/JWKS 互斥选择、配置边界、轮换与错误脱敏测试。
- [ ] 目标环境接入真实企业 SSO/JWKS、密钥轮换策略、组织目录 claim 映射、撤销策略、网络白名单、Secret Manager 和 UAT。
