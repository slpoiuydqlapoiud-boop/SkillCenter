# Security Evidence in Skill Lifecycle Projection

## Goal

将治理域已经持久化的本地安全扫描证据纳入 Skill 生命周期投影与事实源哈希，使安全证据变化能够被关系型投影、离线快照和 reconciliation 检测到，同时保持 metadata-only 与敏感信息不泄露边界。

## Scope

本次变更覆盖 `SkillVersion.securityEvidence` 到 `SkillLifecycleProjectionInput` 的转换、确定性哈希、JSON read-through、PostgreSQL V3 投影 schema 和导入事务。历史 `SkillVersion` 没有安全证据时必须兼容为 `NOT_SCANNED / legacy-compatible / empty findings`。

本次不接入恶意文件引擎、依赖漏洞数据库、许可证服务或生产安全扫描 SLA；也不把 finding 原因、命中内容、凭据、Prompt、Trace 或制品路径之外的敏感正文写入投影。

## Data contract

每个生命周期版本携带安全证据元数据：

- `securityStatus`: 只允许 `PASSED`、`BLOCKED`、`NOT_SCANNED`。
- `securityScannerId` 与 `securityScannerVersion`: 有界稳定标识；旧记录使用 `legacy-compatible` 与空版本。
- `securityFindings`: 零或多个稳定摘要，每项只有 `code`、脱敏 `path`、`severity`（`INFO`/`LOW`/`MEDIUM`/`HIGH`）。

投影模型保留这些字段用于对账，但生命周期查询响应继续沿用现有安全响应边界，不回显 finding 原因、命中片段或秘密值。

## Projection and hash behavior

`SkillLifecycleProjectionSource` 从 `SkillVersion.securityEvidence` 生成版本投影行；哈希 canonicalization 在 `versions` section 中加入 status、scanner id、scanner version 以及按 `code/path/severity` 排序的 finding 摘要。集合顺序变化不得改变哈希，任一安全证据字段变化必须改变哈希。

JSON 与 PostgreSQL 适配器消费同一 `SkillLifecycleProjectionSnapshot` 契约。JSON 继续 read-through；PostgreSQL 导入在一个事务中删除并重建 finding 子表和版本行，避免部分证据可见。

## Persistence migration

新增 Flyway V3：

1. 在 `skill_lifecycle_version_projection` 增加安全状态、扫描器标识和扫描器版本列，并为既有行回填兼容值。
2. 新增 `skill_lifecycle_version_security_finding_projection`，以 `(skill_id, version, finding_index)` 定位稳定摘要，外键指向版本投影。
3. 将 projection metadata schema version 升级为 `3`；旧版本投影不会被隐式切换事实源，下一次显式 import 才会按新 hash 重建。

## Failure and compatibility

源数据安全证据不满足有限枚举、标识或 finding 约束时，source read fail-closed，返回既有 `SKILL_LIFECYCLE_PROJECTION_NOT_READY` 边界，不生成部分快照。旧构造器和缺失 JSON 字段仍通过 legacy 默认值恢复。外部安全扫描未接入时，`NOT_SCANNED` 仍是明确状态，不能被解释为通过。

## Verification

- 单元测试验证源转换、legacy 默认值、finding 顺序稳定和 finding/status/scanner 变化导致 hash 变化。
- JSON store 测试验证快照包含证据并且同一事实不同集合顺序 hash 一致。
- PostgreSQL 测试验证 V3 schema、finding 子表事务导入、round-trip 和删除旧 finding；Docker 不可用时只能保留现有命名 capability skip。
- 统一 verifier 必须保持 0 failures/errors，Web/API 回归与 `git diff --check` 通过。
