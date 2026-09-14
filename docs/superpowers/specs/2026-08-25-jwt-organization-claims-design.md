# JWT Organization Claims Design

状态：平台侧声明映射适配已实现规格；真实企业组织目录、claim 约定、同步时效和 UAT 仍需目标环境验收。

## 目标

让签名 JWT 中的组织/团队声明参与 Skill `TEAM` 范围授权，同时保持本地开发身份和现有两参数 `Actor` 构造兼容。平台只消费签名后的 bounded team identifiers，不保存或返回原始 claims。

## 配置

```yaml
skill-center:
  security:
    authentication:
      jwt:
        team-claim: ${SKILL_CENTER_JWT_TEAM_CLAIM:teams}
        team-claim-required: false
```

- `team-claim` 只能是有界 claim 名称；为空表示不启用组织 claim 映射。
- `team-claim-required=true` 时，缺少 claim 或 claim 不是字符串/字符串数组直接拒绝 Token。
- `false` 时缺少 claim 仍将该 JWT 标记为“组织声明权威但无团队”，TEAM/RESTRICTED 不得因为本地同名用户记录而放行；这避免 SSO 已撤销团队后继续沿用本地成员表。
- 每个 Token 最多 100 个团队，每个团队标识最多 128 字符，并使用平台现有 bounded identifier 规则。

## 数据模型与兼容性

`Actor` 增加不可变的 `teamIds` 与 `teamClaimsAuthoritative` 元数据，并保留 `new Actor(userId, role)` 构造。旧构造创建本地兼容 Actor（空团队、声明非权威）；JWT verifier 创建组织声明权威 Actor。团队 ID 只在服务端授权判定中使用，不进入普通 API 响应、审计字段或错误消息。

## 授权规则

- `PUBLIC` 和 `RESTRICTED` 现有语义不变。
- `TEAM` 先要求本地 `TeamDefinition` 处于 active，防止 JWT 伪造不存在的团队。
- 对 `teamClaimsAuthoritative=true` 的 Actor，仅当 `teamIds` 包含 scope 的 `ownerTeamId` 才视为团队成员；不再回退本地 `memberUserIds`。
- 对旧本地 Actor，继续使用既有本地成员表和 active `RoleBinding` 规则。
- 团队声明只影响可见性；维护/发布等高权限操作仍要求既有本地 maintainer binding 或 admin。

## 安全与验收

- 只接受文本团队 ID 或文本数组；对象、数字、嵌套数组、空值、重复/超量/非法 ID fail-closed。
- JWT 签名、issuer、audience、时间和角色验证先于 Actor 创建。
- 缺少必需团队 claim、未知团队、非 active 团队和组织声明为空都不能泄露 Skill 存在性或绕过范围。
- 静态 PEM 与 JWKS verifier 使用同一 claim 解析逻辑；local 模式保持现有行为。
- 本地测试只证明 claim 映射和授权边界，不代表企业组织目录字段、撤销传播、网络和 UAT 已完成。
