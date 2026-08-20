# M4 分发、个人中心与统计 Beta 设计

## 1. 背景与目标

M3 已完成 Skill 上传、审核、发布、版本和安装请求的治理闭环。M4 的目标是让已发布 Skill 可以被真实分发，并能在不暴露调用内容的前提下形成可核对的安装与调用统计。

本阶段采用“本地可运行、契约可替换”的实现：继续使用当前项目的本地持久化与 Spring API，抽象授权、事件存储和聚合边界；后续接入 SSO、对象存储、消息队列或分析仓库时，不改变前端和客户端契约。

## 2. 范围与非目标

### 2.1 本阶段范围

- 已发布版本的一键、CLI、ZIP 三种分发路径。
- 短期安装授权、客户端兼容性校验、制品哈希校验和授权失效。
- 安装请求状态与安装/升级/降级/卸载事件回传。
- 调用事件批量接入、事件级幂等、重复上报和离线补报。
- 7/30/90 天及自定义日期范围的统计聚合。
- 个人中心的安装清单、当前状态、最近活动和失败原因。
- Skill 版本废弃/下架后的新分发拦截，以及已有安装影响清单。
- 审计记录、请求 ID、错误码和基础指标口径文档。

### 2.2 非目标

- 本阶段不引入真实 SSO/JWT、生产对象存储、Kafka/OpenSearch 或外部通知系统。
- 不在网站内创建或编辑 Skill；Skill 仍只允许从本地 ZIP 上传。
- 不实现复杂的通知中心、审批流编排和跨地域数据仓库。
- 不记录 prompt、文件内容、模型输入输出文本或其他敏感调用载荷。

## 3. 用户与权限

| 用户 | 能力 |
| --- | --- |
| 普通用户 | 查看市场、申请安装、查看本人安装清单和本人事件统计 |
| Skill 维护者 | 查看自己 Skill 的分发与调用统计，查看影响范围 |
| 审核员/管理员 | 查看全局统计、生成安装授权、废弃/下架版本、查看审计记录 |

继续沿用 M3 的 `X-Actor-Id` 与 `X-Actor-Role` 本地角色头；所有新增写操作进入审计日志。

## 4. 关键用户流程

### 4.1 一键安装

1. 用户在 Skill 详情页选择客户端类型和版本，点击“安装”。
2. 服务端检查版本为 `published`、客户端兼容性和角色权限。
3. 服务端创建短期授权（默认 15 分钟，仅允许使用一次），返回安装清单、命令参数、下载地址、SHA-256 和过期时间。
4. 客户端使用授权下载 ZIP，并回传安装事件。
5. 页面轮询或刷新安装状态，显示 `requested / installing / installed / failed / removed`。

### 4.2 CLI 安装

服务端返回可复制的 CLI 命令及短期 token。token 只绑定 Skill、版本、客户端类型和请求用户，不作为长期凭证；成功兑换或过期后立即失效。

### 4.3 ZIP 手动安装

服务端返回带短期授权参数的 HTTPS 下载地址与校验信息。下载接口再次校验授权、版本状态和过期时间；版本下架后立即拒绝新的下载。

### 4.4 事件上报

客户端以批量方式提交安装事件和调用事件。服务端逐条校验，按 `eventId` 幂等去重，返回每条事件的 `accepted / duplicate / rejected` 结果；整批部分失败时不回滚已接受事件，客户端可只补报失败项。

### 4.5 废弃与下架

- `deprecated`：允许已有用户继续使用，停止推荐新安装并在 manifest 中返回原因和替代版本。
- `withdrawn`：立即阻断新授权、manifest 下载和新安装；保留历史安装与统计，返回影响用户/团队/客户端清单。

## 5. API 设计

### 5.1 分发与授权

保留 `POST /api/v1/skills/{skillId}/installations` 作为创建安装请求的入口，响应扩展为：

- `manifest`：与现有 `install-manifest.schema.json` 兼容，新增可选 `distribution` 信息。
- `authorization`：`tokenId`、`expiresAt`、`method`、`downloadUrl`、`consumedAt`。
- `cliCommand`：仅返回脱敏的可复制命令，不返回长期凭证。
- `installationId`：用于查询和事件关联。

新增接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/installations` | 当前用户安装清单；管理员可按用户、团队、Skill、状态筛选 |
| `GET` | `/api/v1/installations/{installationId}` | 安装详情与最新状态 |
| `POST` | `/api/v1/distribution/authorizations/{tokenId}/consume` | 一次性兑换/下载授权 |
| `GET` | `/api/v1/distribution/artifacts/{skillId}/{version}` | 校验授权后下载或返回 ZIP 流 |
| `POST` | `/api/v1/events/installations/batch` | 安装事件批量接入 |
| `POST` | `/api/v1/events/invocations/batch` | 调用事件批量接入 |

下载接口在本地阶段返回项目制品文件流；配置了外部制品地址时返回短期重定向。两种模式共享授权校验、哈希和审计逻辑。

### 5.2 统计

扩展 `GET /api/v1/analytics/overview`：

- `range=7d|30d|90d|custom`
- `from`、`to`（仅 `custom` 必填，按北京时间自然日聚合）
- `skillId`、`teamId`、`clientType` 可选筛选

响应保留现有 `kpis / series / topSkills` 字段，新增：

- `activeUsers`、`activeTeams`、`currentInstallations`、`installationSuccessRate`
- `versionAdoption`、`errorBreakdown`、`latency`（p50/p95）
- `dataQuality`：接受数、重复数、拒绝数、最早/最晚事件时间、是否存在补报

所有时间序列补齐无数据日期，避免前端图表断点；默认范围保持 7 天，保证现有前端兼容。

### 5.3 版本状态

新增管理员接口：

- `POST /api/v1/skills/{skillId}/versions/{version}/deprecate`
- `POST /api/v1/skills/{skillId}/versions/{version}/withdraw`
- `GET /api/v1/skills/{skillId}/versions/{version}/impact`

操作必须带原因，写入审计；下架操作撤销尚未兑换的授权。

## 6. 数据模型与一致性

### 6.1 InstallationRecord 扩展

新增字段：`teamId`、`deviceId`（脱敏摘要）、`method`、`lastEventId`、`lastErrorCode`、`installedAt`、`removedAt`。状态转换限制为：

`requested -> installing -> installed | failed -> installing | removed`，以及 `installed -> upgrading -> installed`。

### 6.2 DistributionAuthorization

字段：`tokenId`、token 摘要、Skill/版本、安装 ID、申请人、客户端、方法、签发时间、过期时间、消费时间、撤销原因。只保存 token 摘要，不保存明文 token。

### 6.3 EventEnvelope

批量请求包含 `batchId`、`schemaVersion`、`events[]`。事件沿用现有 JSON Schema，新增 `receivedAt` 和接收结果，不改变客户端必填字段。

幂等键为 `eventId`；同一 `eventId` 但内容不同视为冲突并拒绝。统计按 `occurredAt` 聚合，接收时间只用于数据质量和补报识别。

### 6.4 本地存储策略

沿用 `GovernanceStore` 的快照/原子写入模式，新增授权、安装状态和事件索引；启动时加载并校验版本，写入失败不覆盖上一个有效快照。事件记录与聚合计算分离，避免重复事件影响计数。

## 7. 前端交互

- Skill 详情页：安装弹窗提供“一键安装 / CLI / ZIP”标签，展示兼容性、权限、哈希、授权倒计时和失败重试。
- 个人中心：安装清单、状态筛选、最近活动、失败原因、重新安装/卸载入口。
- 统计页：范围切换（7/30/90/自定义）、Skill/团队/客户端筛选、调用趋势、成功率、安装转化、版本占比、错误和延迟。
- 管理员版本操作：废弃/下架确认弹窗，显示影响用户/团队数和替代版本。
- 所有请求展示 requestId；批量上报显示“已接收/重复/拒绝”摘要。

## 8. 验收标准

1. 三种分发方式均能生成并校验短期授权；授权过期、重复消费和下架版本下载均返回明确错误码。
2. 安装状态可由客户端事件驱动，重复事件不会重复计数；离线补报能按事件发生日归档。
3. 批量事件支持部分成功，单条非法事件不影响同批合法事件。
4. 7/30/90/自定义统计的日期、时区、成功率、活跃用户、当前安装数和版本占比口径一致。
5. 普通用户只能看到本人数据，维护者只能看到所负责 Skill，管理员可查看全局数据。
6. 废弃版本不再推荐新安装；下架版本立即阻断新授权/下载，并能查询影响范围。
7. 现有 M1-M3 API、前端页面和回归测试继续通过。

## 9. 分阶段交付建议

### M4.1 分发授权与安装状态

短期授权、下载校验、三种分发响应、安装状态查询和安装事件批量接入。

### M4.2 调用事件与统计聚合

批量调用事件、幂等/补报、范围筛选、统计指标与前端看板。

### M4.3 废弃下架与个人中心完善

版本状态操作、影响范围、下架拦截、个人中心和端到端验收。

## 10. 后续替换点

本地 `GovernanceStore` 可替换为关系型数据库；制品流可替换为对象存储短期 URL；事件接入可替换为消息队列；统计聚合可替换为分析仓库；ActorResolver 可替换为 SSO/JWT。以上替换不应改变本文 API 的字段和状态语义。
