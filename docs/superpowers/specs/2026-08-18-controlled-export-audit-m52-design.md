# M5.2 受控导出与审计治理基础设计规格

## 1. 背景与目标

M5.1 已完成团队、分类、标签、可见性与收藏等治理配置。M5.2 在此基础上补齐“谁在什么范围内导出了什么数据、数据保存多久、下载是否可追溯”的基础能力，服务华为开发部门内部 Skill 平台的管理与分发场景。

本阶段目标：

1. 提供受控、异步、可追溯的审计与调用统计导出。
2. 对审计、调用、安装数据实施字段白名单和自动脱敏，禁止导出敏感载荷。
3. 提供可预览、可执行、带权限保护的数据保留策略。
4. 提供管理端导出与保留策略工作台，状态、失败原因和下载入口清晰可见。
5. 为后续限流、防重放、运维监控和外部对象存储保留稳定接口，不在本阶段扩大实现范围。

## 2. 范围与非目标

### 2.1 本阶段范围

- 导出任务创建、排队、执行、查询、下载和过期。
- 审计摘要、调用统计摘要、安装统计摘要三类导出。
- CSV 与 JSON 两种格式，UTF-8 编码。
- 导出请求、执行结果、下载行为和保留策略操作写入审计链。
- 审计/调用/安装数据的保留策略读取、更新、预览与执行。
- 管理端导出工作台和保留策略工作台。
- 本地 JSON 持久化下的重启恢复、失败恢复和过期清理接口。

### 2.2 非目标

- 在网站内创建或编辑 Skill；Skill 仍然只能从本地上传。
- 原始 Prompt、Skill 正文、模型 token 明细、设备指纹、sessionId 等敏感数据导出。
- 通用数据仓库、实时流式导出、跨租户导出。
- 完整的限流、防重放、备份恢复、告警中心和对象存储生命周期管理；这些能力保留到后续阶段。
- 物理删除不可变审计事件。M5.2 的审计保留策略只产生归档资格与清理预览，真正删除/归档迁移需在审计完整性能力稳定后实施。

## 3. 角色与权限模型

当前系统角色为 `viewer`、`maintainer`、`reviewer`、`admin`，没有单独的 auditor 角色。本阶段将 `reviewer` 作为审计操作角色映射，并在接口文档中固定该约定。

| 能力 | viewer | maintainer | reviewer（审计操作） | admin |
| --- | --- | --- | --- | --- |
| 查看自己的普通技能与调用数据 | ✓ | ✓ | ✓ | ✓ |
| 创建审计/调用/安装摘要导出 | - | - | 受可见范围限制 | 全量 |
| 查看导出任务及下载本人创建的任务 | - | - | ✓ | ✓ |
| 下载其他人创建的任务 | - | - | - | ✓ |
| 查看保留策略 | - | - | ✓ | ✓ |
| 更新、预览、执行保留策略 | - | - | - | ✓ |
| 查看失败原因与脱敏字段清单 | - | - | ✓（本人任务） | ✓ |

所有写接口都通过 `ActorResolver` 解析用户与角色，并通过 `RoleGuard` 和资源范围校验。越权场景统一返回现有错误响应格式；不暴露资源是否存在，避免形成 IDOR 信息侧信道。

## 4. 核心数据模型

### 4.1 ExportJob

导出任务作为治理快照的一部分持久化，字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `jobId` | UUID | 任务唯一标识 |
| `dataset` | enum | `AUDIT_SUMMARY`、`INVOCATION_SUMMARY`、`INSTALLATION_SUMMARY` |
| `format` | enum | `CSV`、`JSON` |
| `filters` | object | 时间范围、skillId、teamId、clientType、status 等允许过滤项 |
| `requestedBy` | string | 请求用户 ID |
| `requestedRole` | string | 请求时角色快照 |
| `requestId` | string | 创建请求链路 ID |
| `status` | enum | `QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、`EXPIRED` |
| `createdAt` | OffsetDateTime | 创建时间 |
| `startedAt` | OffsetDateTime | 开始执行时间，可空 |
| `completedAt` | OffsetDateTime | 完成或失败时间，可空 |
| `expiresAt` | OffsetDateTime | 下载凭证与产物过期时间 |
| `artifactPath` | string | 服务端受控产物路径，不返回本地绝对路径 |
| `rowCount` | long | 导出记录数 |
| `sha256` | string | 产物完整性校验值 |
| `redactedFields` | list | 实际应用的脱敏字段名 |
| `downloadTokenHash` | string | 最近一次下载凭证的哈希，不保存明文凭证 |
| `downloadTokenExpiresAt` | OffsetDateTime | 最近一次凭证过期时间 |
| `downloadTokenConsumedAt` | OffsetDateTime | 一次性凭证实际使用时间，可空 |
| `failureCode` | string | 稳定错误码，可空 |
| `failureMessage` | string | 面向操作者的安全提示，不包含堆栈和敏感数据 |

任务状态只能按以下方向迁移：

```text
QUEUED -> RUNNING -> COMPLETED -> EXPIRED
                 \-> FAILED
```

服务重启时，持久化为 `RUNNING` 的任务标记为 `FAILED`（错误码 `EXPORT_INTERRUPTED`）；持久化为 `QUEUED` 的任务重新排队一次，超过重试上限后标记为失败，避免无限重试。

### 4.2 RetentionPolicy

保留策略持久化在治理快照中：

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `policyVersion` | long | 1 | 乐观锁版本 |
| `auditRetentionDays` | int | 365 | 审计事件归档资格窗口；M5.2 不物理删除 |
| `invocationRetentionDays` | int | 90 | 调用明细清理窗口 |
| `installationRetentionDays` | int | 90 | 安装明细清理窗口 |
| `updatedBy` | string | - | 最后修改人 |
| `updatedAt` | OffsetDateTime | - | 最后修改时间 |

安全下限固定为：审计 365 天、调用 30 天、安装 30 天。更新时必须携带当前 `policyVersion`，版本不一致返回 `RETENTION_POLICY_CONFLICT`。

### 4.3 审计完整性元数据

现有 `AuditEvent` 结构保持向后兼容，新增独立的 `AuditIntegrityEntry` 列表，避免破坏已有 JSON 数据：

- `sequence`：单调递增序号。
- `auditId`：对应审计事件 ID。
- `previousHash`：前一条链节点哈希，首节点使用固定 genesis 值。
- `hash`：对规范化后的事件字段和 `previousHash` 做 SHA-256。
- `algorithm`：固定 `SHA-256`。
- `createdAt`：链节点创建时间。

迁移时以当前审计快照建立 genesis 链；之后所有导出、下载、保留策略和治理写操作都必须追加链节点。链校验失败只允许读出“完整性异常”状态，不允许静默修复或覆盖原事件。

## 5. 导出数据白名单与脱敏规则

导出生成器只能从白名单字段组装行数据，禁止“序列化原对象再删除字段”。所有字段组在后端常量中定义，并由测试锁定。

### 5.1 审计摘要

允许字段：`auditId`、`action`、`resourceType`、`resourceId`、`actorRole`、`requestId`、`occurredAt`、安全的 `metadata` 键集合。

默认不导出 `actorId`；如管理员开启内部追责视图，仅以不可逆短哈希形式导出。`metadata` 仅允许 `dataset`、`format`、`rowCount`、`policyVersion` 等平台生成键。

### 5.2 调用统计摘要

按天、Skill、版本、状态、客户端类型和客户端版本聚合，允许字段：`date`、`skillId`、`version`、`status`、`clientType`、`clientVersion`、`count`、`avgDurationMs`、`p95DurationMs`、`errorCount`。

不导出 `sessionId`、用户标识、设备标识、Prompt、响应正文、模型名称、输入/输出 token、原始错误堆栈。

### 5.3 安装统计摘要

按天、Skill、版本、状态、客户端类型和客户端版本聚合，允许字段：`date`、`skillId`、`version`、`status`、`clientType`、`clientVersion`、`count`。

不导出设备标识、下载地址、访问凭证、文件路径和原始请求头。

### 5.4 产物安全

- 文件名使用 `skillcenter-{dataset}-{jobId}.{ext}`，不使用用户输入拼接路径。
- 产物写入应用数据目录下的专用子目录，并校验规范化路径，禁止路径穿越。
- 下载凭证由单独的签发接口生成，服务端只保存令牌哈希；令牌绑定 jobId、请求用户和过期时间，默认有效期 15 分钟且一次性使用。
- 下载响应不缓存，设置 `Content-Disposition`、`Content-Type` 和 `X-Content-Type-Options: nosniff`。
- 过期后保留任务元数据和审计记录，删除或隔离产物文件；重复下载返回 `EXPORT_EXPIRED`。

## 6. API 设计

### 6.1 导出任务

基础路径：`/api/v1/admin/exports`。

- `POST /api/v1/admin/exports`：创建任务。请求包含 `dataset`、`format`、允许的 `filters`；返回 `ExportJob`，初始状态 `QUEUED`。
- `GET /api/v1/admin/exports`：分页查询任务。reviewer 只看到自己创建的任务，admin 可按用户、状态、数据集筛选。
- `GET /api/v1/admin/exports/{jobId}`：查看任务详情、脱敏字段清单和安全失败信息。
- `POST /api/v1/admin/exports/{jobId}/download-url`：为已完成且未过期的任务签发一次性短期下载 URL，并追加 `EXPORT_DOWNLOAD_URL_ISSUED` 审计事件。
- `GET /api/v1/admin/exports/{jobId}/download?token=...`：校验令牌后下载产物；成功或拒绝均追加 `EXPORT_DOWNLOAD` 审计事件。
- `POST /api/v1/admin/exports/{jobId}/retry`：仅 admin 对失败任务重新排队，保留原任务 ID 关联审计。

稳定错误码：`EXPORT_NOT_FOUND`、`EXPORT_FORBIDDEN`、`EXPORT_NOT_READY`、`EXPORT_EXPIRED`、`EXPORT_FILTER_INVALID`、`EXPORT_INTERRUPTED`、`EXPORT_QUEUE_FULL`、`EXPORT_FAILED`。

### 6.2 保留策略

基础路径：`/api/v1/admin/retention`。

- `GET /api/v1/admin/retention`：reviewer/admin 可读。
- `PUT /api/v1/admin/retention`：仅 admin 更新策略，要求 `policyVersion`。
- `POST /api/v1/admin/retention/preview`：仅 admin，返回各数据集符合窗口的记录数、估算释放空间、预计影响时间范围，不修改数据。
- `POST /api/v1/admin/retention/execute`：仅 admin，要求携带预览结果版本或确认令牌；执行调用/安装明细清理，并追加审计。

审计超过窗口的数据只标记为“可归档”，不在 M5.2 物理删除。接口返回 `auditArchiveEligibleCount`，确保治理团队可以先评估容量与归档方案。

保留策略稳定错误码：`RETENTION_POLICY_INVALID`、`RETENTION_POLICY_CONFLICT`、`RETENTION_PREVIEW_EXPIRED`、`RETENTION_EXECUTION_CONFLICT`。

### 6.3 审计查询保护

现有 `/api/v1/audit` 增加：

- reviewer 可按角色允许的资源范围查询脱敏审计摘要。
- admin 可查询全量摘要。
- 不返回原始 metadata 中的敏感键；响应与导出共用白名单投影器。
- 查询条件、分页、导出创建、导出下载、保留策略预览/执行全部写审计事件。

## 7. 后端实现边界

M5.2 继续使用现有 `GovernanceStore` 本地 JSON 文件，扩展快照字段以保证任务元数据、策略和审计追加的原子写入。产物文件与快照分离保存，快照只保存受控相对路径和校验值。

当前 `InvocationEventService` 使用内存 Map。为使调用保留策略可执行，本阶段抽出 `InvocationEventStore` 接口并提供本地 JSON 实现：新快照增加 `invocationEvents` 字段，旧快照缺失时按空列表兼容；摄入、查询、聚合和按时间窗口清理均通过该接口完成。原始调用事件只在服务端受控存储，永不直接进入导出响应。

执行器采用有界线程池：最大并发数 2、队列长度 20、单任务默认超时 60 秒。超过容量返回 `EXPORT_QUEUE_FULL`，不创建半成品任务。服务启动时执行一次任务恢复和过期扫描；后续可替换为消息队列，不改变 API 和状态模型。

调用统计生成器复用 `AnalyticsService` 的可见范围和聚合器；安装摘要复用治理快照安装记录；审计摘要复用白名单投影器。任何导出路径都不得直接暴露 `InvocationEvent`、`InstallationRecord` 或 `AuditEvent` 的完整序列化结果。

## 8. 前端工作台

新增 `AuditExportView`，接入现有管理端导航和 API 封装：

- 导出任务列表：数据集、格式、创建人、状态、记录数、过期时间、失败原因。
- 创建导出：数据集、时间范围、Skill、团队、客户端和状态筛选；前端只展示后端允许的筛选项。
- 任务详情：进度状态、白名单/脱敏字段、校验值、下载按钮。
- 保留策略：当前值、版本、最小值提示、预览结果和执行确认。
- reviewer 可使用审计摘要导出与查看本人任务；viewer/maintainer 不显示导出和保留策略入口。
- 所有错误使用统一的错误码到中文提示映射，不展示堆栈、路径或内部异常消息。

## 9. 验证与验收标准

### 9.1 后端自动化测试

- 角色矩阵：viewer/maintainer 拒绝导出；reviewer 按范围可读；admin 全量可读写。
- 导出字段白名单：三种数据集均不出现 Prompt、Skill 正文、token、sessionId、设备标识和原始 metadata。
- 状态机：排队、运行、完成、失败、重试、过期和重复下载行为符合定义。
- 重启恢复：运行中任务转失败，排队任务最多重试一次，产物路径不能越界。
- 保留策略：最小值校验、乐观锁冲突、预览幂等、执行确认和执行审计。
- 审计完整性：新增链节点顺序、哈希校验、断链检测和历史快照兼容。

### 9.2 前端自动化测试

- 导出列表、创建表单、状态刷新、下载入口、失败提示和权限隐藏。
- 保留策略预览/执行确认、版本冲突和最小值错误提示。
- API 失败时不泄露后端内部信息。

### 9.3 联调验收

使用 admin、reviewer、viewer 三类本地身份完成：创建三类导出、轮询完成、下载、验证 SHA-256、检查审计事件、修改并预览保留策略、执行调用/安装明细清理、重启服务后核对状态持久化。

## 10. 后续阶段接口预留

- 将有界线程池替换为消息队列或独立导出 worker。
- 接入对象存储、下载限流、一次性签名 URL 和企业级密钥管理。
- 增加审计归档仓库、不可抵赖签名、备份恢复演练与容量告警。
- 增加防重放、异常导出检测、运维监控和数据访问审批流。
