# Skill 生命周期持久化控制面设计

日期：2026-08-25  
状态：A1 设计草案

## 1. 目标

为当前本地 JSON 持久化实现增加企业级的版本、迁移、完整性、快照和恢复控制面，使 Skill 资产、版本治理、质量证据、发布批次、运行摘要和优化闭环数据具备可检查、可备份、可恢复的生命周期基础。

本阶段不替换业务 Store 的 JSON 实现，而是先冻结统一的持久化资产目录和运维契约，为 A2 的 PostgreSQL/Flyway 适配器提供稳定边界。

## 2. 当前问题

- API 当前由二十多个互相独立的 JSON Store 组成，每个 Store 自己读取路径、校验结构和原子写入，没有统一 schema 版本清单。
- 启动时可以发现单个 Store 损坏，但无法从平台层面知道哪些资产需要备份、最近一次完整快照是什么、多个 Store 是否属于同一恢复点。
- 业务数据路径散落在 `application.yml` 和构造器 `@Value` 中，迁移、备份和恢复只能依赖人工记忆，无法形成可审计的发布门禁证据。
- 当前没有安全的恢复前置校验：不能在覆盖目标文件前验证快照完整性、目标目录边界和所有关键文件是否齐全。

## 3. 范围

### 3.1 本阶段交付

1. **持久化资产目录**：为 JSON 文件、包目录和指标文件登记稳定 `artifactId`、kind、schemaVersion、路径、是否关键和是否进入快照。
2. **迁移注册表**：保存每个资产已应用的 schema 版本和迁移记录；迁移按 `artifactId + fromVersion + toVersion` 精确匹配，重复执行幂等，缺少迁移或版本倒退 fail-closed。
3. **完整性检查**：对文件计算 SHA-256、字节数、更新时间和 JSON 根结构摘要；对目录生成稳定的文件清单摘要，不读取或返回业务正文。
4. **一致性快照**：在同一快照目录生成所有已登记资产的 manifest 和数据副本；使用临时目录完成后原子改名，禁止产生半成品快照。
5. **恢复前置校验**：恢复前校验快照 ID、manifest 摘要、artifact 集合、目标路径边界和文件哈希；校验失败不得覆盖现有数据。
6. **管理员控制 API**：提供持久化状态、创建快照、查询快照和恢复预检；恢复执行采用运维脚本/服务停机流程，不提供在线覆盖业务数据的 HTTP 动作。
7. **启动门禁**：关键资产缺失、迁移版本不受支持、manifest 损坏或上一次恢复未完成时，应用以明确的 `DEGRADED`/`FAIL_CLOSED` 状态启动；不泄露文件内容和异常堆栈。

### 3.2 不在本阶段

- 不引入 PostgreSQL、Flyway、JPA、Liquibase 或真实对象存储；A2 单独实施关系型适配器。
- 不改变现有业务 API、SkillVersion 状态机、质量门禁、发布准入或范围授权语义。
- 不通过 HTTP 返回 JSON 快照、Skill ZIP、Prompt、输入输出、Trace 正文、工具参数、凭据或 Provider 异常正文。
- 不提供在线无确认恢复、自动回滚业务状态或跨环境复制生产数据。
- 不把 Redis 指标数据伪装成本地 JSON 快照；Redis 后端继续由其自身 TTL/共享存储契约负责，控制面只登记配置和健康状态。

## 4. 核心模型

### 4.1 持久化资产描述

```text
PersistenceArtifactDescriptor
  artifactId: String                 # 稳定小写标识，如 governance-state
  kind: FILE | DIRECTORY
  schemaVersion: int                 # 当前代码支持的版本，必须 >= 1
  storagePath: Path                  # 解析后的绝对路径
  critical: boolean                  # 启动门禁是否阻断
  includeInSnapshot: boolean
```

第一批登记资产：`governance-state`、`skill-scopes`、`skill-relations`、`quality-evidence`、`benchmarks`、`execution-environments`、`releases`、`runtime-summaries`、`optimization-work-items`、`optimization-experiments`、`optimization-observations`、`optimization-assessments`、`operations-metrics` 和 `packages`。调用事件当前持久化在 `governance-state` 的 `invocationEvents` 字段中，不另造重复副本；当 `skill-center.invocation-persistence=false` 时标记为运行时内存能力而非可恢复资产。路径由现有 `skill-center.*-storage` 配置解析；未启用的可选资产允许以 `OPTIONAL_MISSING` 状态存在。

### 4.2 资产状态

```text
PersistenceArtifactStatus
  artifactId
  state: READY | MISSING | CORRUPTED | MIGRATION_REQUIRED | OPTIONAL_MISSING
  schemaVersion
  observedVersion
  sizeBytes
  sha256
  recordCount                     # 仅返回计数，不返回记录正文
  checkedAt
  stableReasonCode
```

`CORRUPTED`、`MIGRATION_REQUIRED` 和关键资产的 `MISSING` 必须阻断 `READY`；状态响应只返回稳定原因码。

### 4.3 快照

```text
PersistenceSnapshotManifest
  snapshotId
  createdAt
  backend: json
  artifactCount
  manifestSha256
  artifacts[]: {
    artifactId, kind, schemaVersion, relativePath,
    sizeBytes, sha256, recordCount
  }
  state: COMPLETE | INCOMPLETE | INVALID
```

快照目录结构固定为：

```text
<snapshot-root>/<snapshotId>/
  manifest.json
  manifest.sha256
  artifacts/<artifactId>/data
```

`relativePath` 只能是 manifest 生成的相对路径；恢复时禁止通过输入参数覆盖目标根目录或跳出配置的存储根。

## 5. 迁移契约

```java
public interface PersistenceMigration {
    String artifactId();
    int fromVersion();
    int toVersion();
    void apply(Path artifactPath);
}
```

- `fromVersion < toVersion`，版本只能前进。
- 同一资产的迁移按版本拓扑排序，每次只执行一跳；不存在路径时返回 `PERSISTENCE_MIGRATION_UNSUPPORTED`。
- 迁移先写同目录临时文件或临时目录，再原子替换正式资产；失败保留原资产并记录 `PERSISTENCE_MIGRATION_FAILED`。
- `migration-journal.json` 只保存 artifactId、from/to 版本、执行时间、结果和 requestId，不保存业务正文。
- A1 自带 `v1 -> v1` 基线登记和可测试的示例迁移执行器；现有业务 JSON 格式的具体升级迁移在 A2 按资产单独加入。

## 6. 运行与 API

### 6.1 配置

```yaml
skill-center:
  persistence:
    backend: json
    control-storage: ./data/control
    snapshot-storage: ./data/backups
    startup-mode: fail-closed
    manifest-retention: 10
```

`backend` 目前只允许 `json`；未知后端、相对路径解析失败、snapshot root 与业务数据根重叠时启动失败。

### 6.2 管理 API

```text
GET  /api/v1/admin/persistence/status
POST /api/v1/admin/persistence/snapshots
GET  /api/v1/admin/persistence/snapshots
GET  /api/v1/admin/persistence/snapshots/{snapshotId}
POST /api/v1/admin/persistence/snapshots/{snapshotId}/restore-preflight
```

所有接口仅限管理员。创建快照必须显式 POST；GET 不触发写入。恢复预检只返回 `READY`/`BLOCKED`、稳定错误码和待恢复资产数量，不执行覆盖。

错误码：`PERSISTENCE_NOT_READY`、`PERSISTENCE_ARTIFACT_MISSING`、`PERSISTENCE_ARTIFACT_CORRUPTED`、`PERSISTENCE_MIGRATION_REQUIRED`、`PERSISTENCE_MIGRATION_UNSUPPORTED`、`PERSISTENCE_SNAPSHOT_NOT_FOUND`、`PERSISTENCE_SNAPSHOT_INVALID`、`PERSISTENCE_RESTORE_BLOCKED`。

## 7. 安全与一致性

- 快照操作在写入临时目录期间使用文件锁；manifest、摘要和副本必须全部写完后才可见。
- 不允许把 snapshot ID、artifact ID 解析为未校验的路径；所有目标都必须位于配置根目录内。
- API 只返回状态、计数、大小、摘要和稳定原因码；不返回快照下载地址、文件名中的业务内容或原始异常。
- 关键资产的完整性检查失败时，应用不提供会继续写入业务数据的 READY 状态；运维必须先恢复或明确切换到修复模式。
- 快照和迁移审计只记录操作人、requestId、资产 ID、版本、结果和计数，不记录 Skill 内容、Prompt、Trace 或凭据。
- 既有业务 Store 的单文件原子写入和领域校验继续保留；控制面是附加门禁，不绕过 Store 的校验。

## 8. 测试与验收

### 后端

- 资产目录：路径边界、稳定排序、可选缺失、关键缺失和未知 backend。
- 完整性：文件/目录 SHA-256、JSON 根结构摘要、大小/计数，损坏文件返回稳定状态。
- 迁移：版本前进、重复幂等、缺少路径、迁移失败保留原文件、journal 恢复。
- 快照：多资产完整复制、临时目录原子发布、半成品不可见、manifest 摘要可复核。
- 恢复预检：错误 artifact、哈希不匹配、路径穿越、缺失 manifest 均不覆盖目标。
- API：管理员权限、GET 无副作用、创建快照、查询状态、稳定错误码和无正文响应。
- 全量 API 回归，Windows 使用既有 `-DforkCount=0` 规则。

### 验收标准

1. 新环境首次启动可以登记全部 JSON 资产，状态可查询，业务 API 行为保持不变。
2. 任一关键资产损坏或迁移不受支持时，平台返回可诊断的 fail-closed 状态，不继续伪装为健康。
3. 完整快照只在所有登记资产校验成功后出现，重启后仍可查询并复核 SHA-256。
4. 恢复预检对任何不完整/篡改快照阻断，且不修改现有文件。
5. 迁移 journal、快照 manifest 和审计记录不包含业务正文与凭据。
6. A2 可以在不改变业务 API 的前提下，为同一 `artifactId` 替换 PostgreSQL/Flyway 实现。
