# 组织目录快照同步设计

日期：2026-08-25  
状态：平台侧适配实施中；真实企业目录、网络和撤销时效仍需目标环境验收

## 目标与边界

将企业组织目录变成 Skill TEAM 范围授权可消费的、可审计的本地事实快照。请求路径不访问外部目录；管理员显式触发同步，平台对响应做有界校验后原子替换快照。目录不可用、响应非法、revision 回退或快照过期时，HTTP 目录模式必须 fail-closed，不回退到旧的本地成员表。

本阶段不实现跨团队审核编排、目录自动推送、后台调度器或组织目录的全量用户画像同步；只同步 TEAM 授权所需的团队状态和成员 ID。

## 数据契约

外部响应使用 `organization-directory.v1`：

```json
{
  "schemaVersion": "organization-directory.v1",
  "source": "corp-directory",
  "revision": "2026-08-25T06:00:00Z",
  "fetchedAt": "2026-08-25T06:00:01Z",
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

顶层和团队对象均采用字段 allowlist；未知字段、重复团队、重复成员、非法 ID、空 revision、错误 schema、非 2xx、超大响应和不完整 JSON 均拒绝。单次响应最多 5,000 个团队、每团队 10,000 个成员、总成员 200,000 个；ID 使用平台 bounded identifier 规则，名称只保存有界展示文本。

## 配置与同步

```yaml
skill-center:
  governance:
    organization-directory:
      mode: local
      endpoint: ${SKILL_CENTER_ORGANIZATION_DIRECTORY_ENDPOINT:}
      credential-ref: ${SKILL_CENTER_ORGANIZATION_DIRECTORY_CREDENTIAL_REF:}
      connect-timeout-ms: 1000
      request-timeout-ms: 2000
      max-response-bytes: 1048576
      max-age-seconds: 900
      storage: ./data/governance/organization-directory.json
```

- `local` 为默认模式，保持现有 GovernanceConfiguration 的本地成员路径。
- `http` 模式必须配置 HTTPS endpoint（loopback 测试允许 HTTP）和 `secret://...` credential reference；服务端使用 GET + Bearer，不把 credential 原文写入日志、审计或响应。
- 只有显式 `POST /api/v1/admin/governance/organization-directory/sync` 才发起同步；`GET /api/v1/admin/governance/organization-directory` 只读状态。
- 首次同步成功后快照进入 `ACTIVE`；达到 `max-age-seconds`、最近同步失败或检测到 revision 回退时状态为 `STALE`/`FAILED`，授权拒绝。
- 相同 revision 且内容 hash 相同的同步幂等；相同 revision 内容不同或更旧 revision 拒绝，不替换当前快照。

## 授权语义

TEAM 可见性继续要求本地 SkillScope 的 `ownerTeamId` 对应 active `TeamDefinition`。在 `local` 模式保持原有成员表和 active RoleBinding 语义；在 `http` 模式必须同时满足：

1. 本地 TeamDefinition active；
2. 目录快照 active 且未过期；
3. 目录快照包含 actor 对该团队的成员关系；
4. JWT `teamClaimsAuthoritative=true` 时，签名 `teamIds` 也必须包含该团队。

管理、发布和提交仍额外要求现有本地 maintainer binding 或 admin。目录同步只改变成员事实，不自动授予角色、不修改 Skill scope、不自动发布或撤销版本。

## 持久化与安全

快照和同步状态使用独立 JSON 原子存储，保存 source、revision、hash、时间、团队/成员计数和稳定状态码，不保存原始 HTTP 响应、Token、用户属性或外部异常正文。失败状态可以保留上一个快照用于诊断，但授权层只消费 `ACTIVE` 且未过期的快照。

管理员同步审计只记录 source、revision、计数、状态和稳定 reason code。HTTP transport 限制 URL、超时、响应大小和状态码，并统一脱敏异常。

## 验收证据

- 快照 record：边界、排序、不可变、重复与上限校验。
- Store：原子写入、重启恢复、重复/回退 revision、失败状态和过期判断。
- HTTP adapter：URL、Bearer、超时、非 2xx、超大/非法 JSON、allowlist 和敏感字段不回显。
- Sync service/controller：管理员权限、幂等同步、失败不替换、稳定响应和审计投影。
- SkillAuthorizationService：HTTP 模式必须目录成员、JWT claim 与目录双重命中，local 模式兼容，过期/失败 fail-closed。
- 全量 API/Web/lifecycle verifier 保持通过；本地测试不宣称真实目录、网络白名单、Secret Manager 或撤销 SLA 完成。
