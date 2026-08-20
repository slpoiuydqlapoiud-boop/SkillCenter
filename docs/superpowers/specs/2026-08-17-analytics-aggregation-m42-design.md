# M4.2 调用事件与统计聚合设计

## 1. 目标

在 M4.1 已完成调用事件批量接入、事件级幂等和安装状态回传的基础上，交付可核对的统计查询接口与管理看板。平台管理员可按时间范围、Skill、团队和客户端查看调用趋势、成功率、活跃主体、安装转化、版本采用、错误和延迟；普通用户和 Skill 维护者沿用现有角色边界，只能查看授权范围内的数据。

本阶段继续采用本地可运行、契约可替换的实现：调用明细使用当前进程内事件服务，安装状态使用 `GovernanceStore` 持久化快照，聚合边界通过 `AnalyticsQuery` 和 `AnalyticsService` 保持稳定，后续可替换为数据库或分析仓库而不改前端契约。

## 2. 范围与非目标

### 2.1 本阶段范围

- `GET /api/v1/analytics/overview` 支持 7 天、30 天、90 天和自定义日期范围。
- 支持 `skillId`、`teamId`、`clientType` 筛选。
- 按北京时间（`Asia/Shanghai`）自然日聚合，日期序列补齐无数据日期。
- 保留现有 `kpis`、`series`、`topSkills` 字段，并扩展活跃用户/团队、当前安装、安装成功率、版本采用、错误分布、p50/p95 延迟和数据质量。
- 管理看板增加范围切换、自定义日期筛选和扩展指标展示。
- 为时区、范围校验、过滤、聚合、百分位数和前端查询参数补充自动化测试。

### 2.2 非目标

- 不记录或展示 prompt、输出正文、文件内容、凭据、模型输入输出文本或其他敏感调用载荷。
- 不在本阶段引入真实 SSO/JWT、消息队列、对象存储、分析仓库或定时离线任务。
- 不改变事件接入协议；事件接收仍以 `eventId` 幂等，统计只消费已接受的唯一事件。
- 不实现版本废弃/下架及影响面操作，这些属于 M4.3。

## 3. 角色与权限

统计接口复用现有 `ActorResolver` 与角色模型：

| 角色 | 可见范围 |
| --- | --- |
| `viewer` | 当前用户自己的调用、安装和相关 Skill 数据 |
| `maintainer` | 自己负责的 Skill；团队筛选只能落在负责范围内 |
| `reviewer` | 全局统计只读 |
| `admin` | 全局统计只读 |

M4.2 的本地演示继续使用现有请求头角色。聚合服务接收已解析的 `Actor`，不得通过前端传入的筛选条件绕过权限。若筛选条件与角色范围无交集，返回空结果而不是泄露其他团队的计数。

## 4. 查询契约

### 4.1 请求

`GET /api/v1/analytics/overview`

查询参数：

| 参数 | 取值 | 默认/规则 |
| --- | --- | --- |
| `range` | `7d`、`30d`、`90d`、`custom` | 缺省为 `7d` |
| `from` | `YYYY-MM-DD` | 仅 `custom` 必填，按北京时间解释 |
| `to` | `YYYY-MM-DD` | 仅 `custom` 必填，按北京时间解释 |
| `skillId` | Skill slug | 可选 |
| `teamId` | 团队 slug | 可选 |
| `clientType` | 已登记客户端类型 | 可选 |

规则：

1. 固定范围以“今天（北京时间）”为结束日，包含结束日，`7d` 表示今天及之前连续 6 天。
2. `custom` 的 `from` 和 `to` 均包含，`from <= to`，跨度最多 90 个自然日。
3. 非 `custom` 传入 `from`/`to` 视为无效请求，避免客户端误用造成隐式口径。
4. 日期格式、范围枚举、跨度和筛选值非法时返回统一 `INVALID_REQUEST`，不执行部分聚合。
5. 统计时按事件 `occurredAt` 归属自然日；事件的接收时间不改变趋势日期。

### 4.2 响应

响应继续包装在 `ApiResponse.data` 中：

```json
{
  "kpis": {
    "calls": 120,
    "successfulCalls": 116,
    "successRate": 96.67,
    "activeSkills": 4,
    "activeUsers": 18,
    "activeTeams": 3,
    "currentInstallations": 26,
    "installationSuccessRate": 91.3
  },
  "series": [
    {"day": "08-11", "calls": 0, "successRate": 0, "activeUsers": 0, "installations": 0}
  ],
  "topSkills": [
    {"id": "topology-analysis", "name": "拓扑分析", "calls": 80, "successRate": 97.5}
  ],
  "versionAdoption": [
    {"skillId": "topology-analysis", "version": "1.2.0", "calls": 50, "percentage": 62.5}
  ],
  "errorBreakdown": [
    {"errorCode": "UPSTREAM_TIMEOUT", "count": 3, "percentage": 75.0}
  ],
  "latency": {"sampleCount": 120, "p50Ms": 210, "p95Ms": 890, "maxMs": 2100},
  "dataQuality": {
    "accepted": 120,
    "duplicates": 7,
    "rejected": 2,
    "earliestOccurredAt": "2026-08-11T01:00:00Z",
    "latestOccurredAt": "2026-08-17T09:00:00Z",
    "hasBackfill": true
  }
}
```

兼容约束：原有 `Kpis` 的四个字段、`DayPoint.day/calls/successRate` 和 `TopSkill` 字段保留，新增字段使用默认值或新增 record 字段序列化。旧客户端只读取原字段时仍可正常渲染。

## 5. 统计口径

### 5.1 调用

- 过滤后的 `InvocationEvent` 集合即为已接受且按 `eventId` 去重的明细。
- `calls` 为事件数；`successfulCalls` 仅统计 `status=success`。
- `successRate = successfulCalls / calls * 100`，四舍五入到两位小数；分母为 0 时返回 0。
- `activeSkills` 为范围内至少有一次调用的 Skill 数；无事件时兼容使用已发布目录的旧指标仅用于默认空数据展示，不影响有事件范围的真实聚合。
- `activeUsers`/`activeTeams` 分别按 `subject.userId`/`subject.teamId` 去重；空主体不计入。

### 5.2 安装与转化

- `currentInstallations` 从 `GovernanceStore.snapshot().installations()` 计算，表示查询时刻的当前状态，不按日期范围截断；状态为 `installed` 或 `installing` 的记录计入，`removed` 和 `failed` 不计入。若传入 `skillId/teamId/clientType`，当前安装必须匹配对应维度。
- 安装转化按 `requestedAt` 的北京时间日期过滤；若筛选 `skillId/teamId/clientType`，必须同时匹配记录对应字段。
- `installationSuccessRate` 以范围内有安装请求的记录为分母，以同一批记录中状态为 `installed` 的记录为分子；没有请求时返回 0。当前安装事件明细没有独立持久化表，因此不再声称可以从历史事件判断“成功事件是否存在”。
- 安装事件当前没有独立的持久化明细表，因此转化统计使用安装记录的状态和时间字段，并在数据质量说明中标记该边界。

### 5.3 版本、错误与延迟

- `versionAdoption` 按 Skill 与版本统计调用数，百分比以筛选范围内总调用数为分母，按调用数降序、版本号升序稳定排序。
- `errorBreakdown` 仅统计失败和超时事件的非空 `errorCode`，按数量降序、错误码升序稳定排序；取消事件不虚构错误码。
- 延迟从 `durationMs` 计算，排序后取最近秩（nearest-rank）p50/p95；`sampleCount=0` 时 p50、p95、max 均为 0。
- 所有趋势序列按范围生成完整日期列表，无事件日期返回 0，避免前端折线/柱状图断点。

### 5.4 数据质量

`InvocationEventService` 增加进程内的轻量接收摘要，逐次记录 `eventId`、可解析的 `occurredAt`、接收时间和结果（accepted/duplicate/rejected）；不保存调用正文。`accepted`、`duplicates`、`rejected` 在当前查询范围内按该摘要累计，无法解析发生时间的拒绝项只计入全局质量计数并在响应中保持可见。当前服务只持有进程内摘要与去重索引，历史重启后的质量明细无法追溯，因此 `dataQuality` 和页面提示均标记“本地运行时口径”。`hasBackfill` 仅在摘要中存在 `occurredAt` 早于对应接收时间且不属于当前自然日时置为 `true`；没有接收时间证据时为 `false`。

## 6. 后端结构

新增 `AnalyticsQuery` 负责解析后的不可变查询条件，包含 `RangeType`、`from`、`to`、`skillId`、`teamId`、`clientType` 和 `ZoneId`。新增 `AnalyticsAggregator`（或等价的无状态聚合组件）负责：

1. 过滤事件和安装记录。
2. 计算 KPI、日期序列、热门 Skill、版本、错误和延迟。
3. 输出扩展后的 `AnalyticsOverview`。

`AnalyticsController` 只做查询参数绑定、日期/枚举错误转 `INVALID_REQUEST`、actor 解析和调用服务；不包含聚合循环。`AnalyticsService.overview(AnalyticsQuery, Actor)` 作为唯一业务入口，并保留无参 `overview()` 委托默认 7 天，保证现有测试和调用兼容。

## 7. 前端交互

- `skillApi.getAnalyticsOverview(params)` 通过现有 `queryString` 编码参数。
- `AnalyticsView` 使用受控范围选择：最近 7 天、30 天、90 天、自定义；自定义显示两个日期输入，提交时校验起止和 90 天上限。
- 范围改变后重新请求接口，保留上一次成功数据，加载时显示轻量状态，失败时显示错误提示。
- KPI 卡片展示调用量、成功率、活跃 Skill、活跃用户、当前安装和安装成功率；趋势图继续显示完整日期序列。
- 在热门 Skill 下增加版本采用、错误分布、延迟摘要和数据质量摘要，空数据展示明确的“暂无事件”状态。
- 导出按钮在本阶段保持禁用或提示“导出将在后续阶段开放”，不伪造下载文件。

## 8. 错误与可观测性

- 非法范围、日期、筛选参数返回 `400 INVALID_REQUEST`，带 `requestId`。
- 聚合异常由现有全局错误处理器转为统一 `INTERNAL_ERROR`，不返回事件正文。
- 统计响应不返回 `sessionId`、设备 ID、token、提示词或输出正文；仅返回匿名计数和已允许的维度。
- 记录聚合耗时和查询条件摘要（不记录敏感字段）到现有应用日志，便于后续迁移分析仓库。

## 9. 测试与验收

### 9.1 后端

- 默认查询返回 7 个北京时间日期，跨 UTC 日界线的事件归属正确。
- `30d`、`90d` 和 custom 日期范围长度、包含式边界和 90 天上限正确。
- `skillId`、`teamId`、`clientType` 组合筛选只保留匹配事件。
- 成功率、活跃用户/团队、版本百分比、错误计数和 p50/p95 与固定事件样本一致。
- 当前安装、安装成功率按状态和范围正确；空分母返回 0。
- 非法参数返回 `INVALID_REQUEST`；旧的无参接口测试继续通过。

### 9.2 前端

- API 客户端正确编码范围和筛选参数。
- 范围选择更新查询并显示对应日期数量；自定义日期非法时不发请求。
- 扩展指标在缺省/空数据响应下不抛异常，旧响应仍可渲染。

### 9.3 验收命令

```text
mvn -B -q -f apps/api/pom.xml test
npm.cmd test --prefix apps/web
npm.cmd run build --prefix apps/web
```

另需使用运行中的 API 进行一次 7d、30d、custom 和非法范围 smoke test，确认响应状态码、`requestId` 和北京时间日期序列。

## 10. 后续替换点

M4.2 交付后，事件服务可将 `events()` 替换为按时间和维度索引的查询端口；`AnalyticsAggregator` 的输入输出不变。安装事件 receipt、接收计数和拒绝计数迁移到持久化表后，`dataQuality` 可从“当前进程可见”升级为全量可靠统计；前端无需变更。
