# A1 Persistence Control Plane 状态

日期：2026-08-25

## 已交付边界

A1 在现有本地 JSON Store 之上增加只读的资产目录完整性检查、migration journal 校验、状态聚合、快照元数据控制 API 和启动门禁。业务 Store 的 JSON 格式、原子写入和领域校验没有被替换；普通 Skill、质量和持久化控制面 GET 不会创建快照、补写 migration journal 或执行迁移。

启动和 `GET /api/v1/admin/persistence/status` 共用 `PersistenceControlService` 的状态计算。Spring lifecycle 在应用启动时读取 backend/config roots、已登记资产和 journal；`fail-closed` 模式下 `FAIL_CLOSED` 会阻止应用完成启动，`DEGRADED` 允许本地应用继续启动并通过管理员状态接口暴露原因。检查不会创建目录、修复 JSON、覆盖业务文件或在线恢复数据。

## 配置

默认本地配置如下，路径按 `configured-root` 解析并规范化：

```yaml
skill-center:
  persistence:
    backend: json
    configured-root: ${user.dir}
    control-storage: ./data/control
    snapshot-storage: ./data/backups
    startup-mode: fail-closed
    manifest-retention: 10
```

当前只支持 `backend: json`。control、snapshot、业务资产路径必须位于配置根内；空路径、路径穿越、符号链接逃逸、非法 backend 或控制面 wiring 异常都不能产生 `READY`。`manifest-retention` 默认保留最近 10 个快照；`control-storage/snapshots.json` 中的 metadata 与 snapshot directories/data copies 采用同一留存和淘汰行为，淘汰前会验证对应 manifest 为 `COMPLETE`，随后删除该完整 snapshot directory。该策略不代表业务数据可在线删除，也不替代外部备份保留策略。

## 状态和稳定原因码

总体状态：

- `READY`：所有已检查资产可读且版本受支持，没有控制面错误。
- `DEGRADED`：仅存在非关键资产的 `OPTIONAL_MISSING`。
- `FAIL_CLOSED`：关键资产缺失/损坏，任意资产损坏或迁移不受支持，journal 损坏，非法路径/backend，或控制面 wiring 异常。

资产状态包括 `READY`、`MISSING`、`CORRUPTED`、`MIGRATION_REQUIRED`、`MIGRATION_UNSUPPORTED` 和 `OPTIONAL_MISSING`。响应只返回 artifact ID、版本、大小、SHA-256、计数、检查时间和稳定原因码，不返回路径、JSON 正文、Skill 内容、Prompt、Trace、工具参数、Token、凭据或原始异常。

重要原因码：`PERSISTENCE_ARTIFACT_MISSING`、`PERSISTENCE_ARTIFACT_CORRUPTED`、`PERSISTENCE_MIGRATION_REQUIRED`、`PERSISTENCE_MIGRATION_UNSUPPORTED`、`PERSISTENCE_NOT_READY`、`PERSISTENCE_RESTORE_BLOCKED`、`PERSISTENCE_SNAPSHOT_NOT_FOUND`、`PERSISTENCE_SNAPSHOT_INVALID`。

## Metadata-only API 与留存

管理员接口：

```text
GET  /api/v1/admin/persistence/status
POST /api/v1/admin/persistence/snapshots
GET  /api/v1/admin/persistence/snapshots
GET  /api/v1/admin/persistence/snapshots/{snapshotId}
POST /api/v1/admin/persistence/snapshots/{snapshotId}/restore-preflight
```

只有显式 `POST /snapshots` 创建快照；GET 只读取状态或快照元数据。快照 manifest 记录 artifact 集合、schema version、大小、摘要和计数；`manifest-retention` 同时约束 `snapshots.json` metadata 和对应完整 snapshot directory/data copies，淘汰前验证已发布的 `COMPLETE` snapshot directory 后删除其目录。控制面 API 仍只返回 metadata，不提供快照下载、业务正文返回或在线覆盖动作。

## Offline restore-preflight / no online restore

`restore-preflight` 只校验 snapshot ID、manifest 摘要、artifact 集合、相对路径边界和每个副本的哈希，返回 `READY`/`BLOCKED`、稳定错误码和资产数量；它不会改写 live JSON、切换服务状态或自动回滚。

实际恢复必须是停机或隔离环境中的运维流程：先停止写入、复制并保留当前数据、离线验证快照和目标根，再由受审批的脚本/人工流程原子替换，并重启执行 A1 startup gate。任何 manifest、哈希、路径、符号链接或资产集合不匹配都必须停止流程；A1 不提供 online restore，也不做破坏性数据替换。

所有路径都必须规范化并位于 configured root；snapshot ID、artifact ID 不能直接拼接未校验输入。沿路径的现有目录和文件如果是符号链接，检查和恢复前置校验均阻断，防止跳出配置根。

## A2 handoff 和未完成外部事项

A2 可以沿用稳定的 artifact ID、schema version、状态码、metadata-only API 和 offline restore-preflight 边界，新增 PostgreSQL/Flyway adapter；A1 没有实现 PostgreSQL、Flyway、JPA、在线数据库迁移或生产数据切换。A2 需要保持同一资产目录语义，并为数据库/对象存储分别定义事务、一致性快照、备份和恢复演练契约。

企业 SSO/JWT、生产 PostgreSQL、对象存储、Redis 多实例、真实 OpenClaw/DeepEval/Langfuse Provider、生产密钥管理、安全扫描、监控告警、备份恢复演练、性能/UAT 和上线审批仍是外部前置条件。本地 Mock/JSON 回归只证明控制面契约和安全边界，不代表这些生产能力已经接通。

## A2 Phase 1 addendum（2026-08-25）

本节补充 A2 事实，不改变上文 A1 的历史 JSON 文件快照表述。JSON 仍是默认后端且不需要 datasource URL。PostgreSQL 质量证据只有在同时设置 `skill-center.persistence.backend=postgresql` 和 `skill-center.quality-evidence-backend=postgresql` 时才启用；URL、用户名和密码必须由环境变量或密钥管理系统提供。缺失或非法配置、连接或 Flyway 状态异常均 fail-closed，并只暴露脱敏的稳定状态/原因码。

Flyway V1 创建 `skill_quality_evidence_state` 单例 JSONB 聚合；质量证据仓库写入具备事务和 revision 语义。PostgreSQL 质量证据不会被复制到 A1 文件快照：创建快照和 restore-preflight 对该后端明确返回 unsupported/blocked。本阶段不能把本地测试或 Testcontainers 能力跳过表述为生产数据库、备份或恢复就绪。
