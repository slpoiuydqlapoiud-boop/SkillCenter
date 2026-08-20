# Skill Center M2 真实后端接口接入设计

日期：2026-08-17  
状态：待用户审核

## 目标

在 M1 前端原型和 M0 JSON Schema 契约的基础上，交付一个可本地运行的真实后端纵切，使“市场列表 → Skill 详情 → ZIP 上传校验 → 安装清单 → 调用统计”主链路不再依赖前端 mock 数据。

## 范围

### 本阶段包含

- Java 21 + Spring Boot 3 API 工程，路径为 `apps/api`。
- Skill 列表、详情和统计接口。
- 本地 ZIP 上传、包结构安全检查、`SKILL.md`/`skill.json` 检查和 M0 Schema 校验入口。
- 安装清单生成接口，响应遵循 `install-manifest.schema.json`。
- 调用事件接收接口，响应和校验遵循 `invocation-event.schema.json`。
- 内存 Skill 仓储、内存调用事件聚合、本地临时包存储。
- 前端 API client、Vite `/api` 代理、加载/错误/空状态和关键动作联调。
- 单元测试、MockMvc API 测试、前后端本地联调验证。

### 本阶段不包含

- 企业 SSO、组织目录和真实角色同步。
- PostgreSQL、Redis、Kafka、OpenSearch、S3 等基础设施接入。
- 真实审批工作流、审核人分配和生产级病毒扫描。
- 真实 Agent/Codex 客户端回调或跨网络安装。
- 线上部署、TLS、生产密钥和部门级权限策略。
- 网站内创建或编辑 Skill，或引入草稿状态。

## 方案选择

### 推荐：Spring Boot 纵切 + 可替换适配器

使用 Spring Boot 3 建立企业 API 边界；Repository、PackageStorage 和 EventStore 均定义接口，M2 使用内存实现和本地临时目录。这样可以先完成真实 HTTP 联调，同时不把 PostgreSQL、对象存储或消息队列耦合进前端和领域服务。

### 未选择的方案

- Node.js 临时 API：启动快，但偏离已冻结的 Java/Spring Boot 企业技术栈，后续迁移会重复实现校验和错误模型。
- M2 直接接全套基础设施：真实度更高，但需要 SSO、数据库、对象存储和消息系统的环境信息，无法在当前工作区独立验收。

## 系统边界

```text
React/Vite Web
    │  /api/v1/*
    ▼
Spring Boot Core API
    ├── SkillCatalogService ── InMemorySkillRepository
    ├── PackageValidationService ── LocalPackageStorage
    ├── DistributionService ── InstallManifestFactory
    ├── InvocationEventService ── InMemoryInvocationStore
    └── AnalyticsService
```

后续接入 PostgreSQL、S3、Kafka 或企业 SSO 时，只替换适配器实现，不改变 Web API 的资源路径和 M0 事件语义。

## HTTP 接口

### 1. 查询 Skill 列表

`GET /api/v1/skills?query=&category=&status=&risk=&page=1&pageSize=12`

响应：

```json
{
  "data": {
    "items": [
      {
        "id": "eox-query",
        "name": "EOX查询Skill",
        "version": "1.2.0",
        "description": "根据自然语言查询设备 EOX 状态",
        "category": "网络运维",
        "tags": ["网络运维", "只读查询"],
        "risk": "低风险",
        "team": "网络智能团队",
        "status": "published",
        "metrics": {"rating": 4.8, "reviews": 76, "calls": 12500, "installs": 680, "favorites": 235}
      }
    ],
    "page": 1,
    "pageSize": 12,
    "total": 12
  },
  "requestId": "uuid"
}
```

筛选参数为空时返回全部已发布 Skill；`pageSize` 上限为 50；未知分类返回空列表而不是 500。

### 2. 查询 Skill 详情

`GET /api/v1/skills/{skillId}`

返回市场卡片之外的权限、能力、适用场景、输入输出示例、所属合集、发布时间和最近更新时间。不存在时返回 `404 SKILL_NOT_FOUND`。

### 3. 上传 Skill ZIP

`POST /api/v1/skill-packages`  
请求：`multipart/form-data`，字段名 `file`。

校验规则：

1. 文件扩展名必须为 `.zip`，大小不超过 20 MiB。
2. ZIP 只能有一个安全根目录；有 `skill.json` 时，根目录名必须等于 `skill.json.id`，没有时使用根目录名作为 Skill ID。
3. 拒绝绝对路径、`..` 路径、符号链接条目、嵌套 ZIP 和超出解压大小限制的压缩炸弹。
4. 根目录必须包含 `SKILL.md`，`skill.json` 为可选元数据文件。
5. 存在 `skill.json` 时按 `contracts/schemas/v1/skill.schema.json` 校验；缺少时使用根目录名作为 `skillId`、`1.0.0` 作为初始版本。`SKILL.md` 必须包含符合约定的 `name` 和 `description` frontmatter。
6. 校验通过后写入本地临时包目录，状态为 `validated`；不在本阶段自动发布。

成功响应：`201 Created`，返回 `packageId`、`skillId`、`version`、`status` 和校验摘要。失败返回 `400 PACKAGE_VALIDATION_FAILED`，包含可展示的字段级错误，不返回文件内容。

### 4. 生成安装清单

`POST /api/v1/skills/{skillId}/installations`

请求体：

```json
{"clientType":"codex","clientVersion":"1.0.0","method":"one-click"}
```

服务端从已发布 Skill 元数据生成 `install-manifest.schema.json` 结构，包含版本、下载 URL、SHA-256、大小、兼容性、权限、依赖、签发时间和过期时间。M2 使用本地下载地址和演示签名字段，不写入真实凭据。

### 5. 接收调用事件

`POST /api/v1/events/invocations`

请求体必须完整符合 `contracts/schemas/v1/invocation-event.schema.json`。服务端按 `eventId` 幂等去重：首次返回 `202 Accepted`，重复事件返回 `200 OK` 且 `duplicate=true`。禁止 prompt、output、文件内容、token、凭据等未声明字段。

### 6. 查询统计概览

`GET /api/v1/analytics/overview?from=2026-08-11&to=2026-08-17`

返回：

```json
{
  "data": {
    "kpis": {"calls": 39520, "successRate": 98.1, "activeSkills": 86, "activeUsers": 1284},
    "series": [{"day":"08-11","calls":4380,"successRate":96.8}],
    "topSkills": [{"skillId":"eox-query","calls":12500,"successRate":98.6,"trend":28.2}]
  },
  "requestId": "uuid"
}
```

统计仅基于已接收的脱敏事件，不保存请求正文或输出正文。

## 错误模型

所有非 2xx 响应使用：

```json
{
  "error": {
    "code": "PACKAGE_VALIDATION_FAILED",
    "message": "Skill 包校验失败",
    "details": [{"path":"skill.json.version","reason":"invalid semver"}]
  },
  "requestId": "uuid"
}
```

固定错误码：`INVALID_REQUEST`、`SKILL_NOT_FOUND`、`PACKAGE_TOO_LARGE`、`PACKAGE_VALIDATION_FAILED`、`EVENT_SCHEMA_INVALID`、`INSTALLATION_NOT_AVAILABLE`、`INTERNAL_ERROR`。

## 前端接入设计

- 新增 `src/api/client.js`：统一 base URL、超时、JSON 解析、`requestId` 和错误转换。
- 新增 `src/api/skillApi.js`：封装 `listSkills`、`getSkill`、`uploadPackage`、`createInstallation`、`getAnalyticsOverview`、`ingestInvocation`。
- `App.jsx` 由 API 响应驱动市场、详情和统计，不再把 mock 数据作为运行时数据源。
- M1 的 `state.js` 保留纯函数测试和展示转换工具；种子 Skill 数据移动到后端 `src/main/resources/skills.json`。
- `vite.config.mjs` 增加 `/api` 代理到 `http://127.0.0.1:8080`。
- 请求失败时显示可重试错误态；首次加载显示骨架/加载态；空结果显示空态。

## 测试策略

### 后端

- `PackageValidationServiceTest`：合法包、缺少 `SKILL.md`、非法 SemVer、路径穿越、嵌套 ZIP、超限包。
- `InvocationEventServiceTest`：合法事件、未知字段、缺失失败码、重复 `eventId`。
- `SkillControllerTest`：列表筛选、详情 404、上传 201/400、安装清单、统计响应。
- `SchemaContractTest`：读取 M0 Schema 和样例，验证 API DTO 的关键字段。

### 前端

- API client 测试：成功响应、非 2xx 错误、超时和上传 multipart。
- 状态层测试继续覆盖筛选、角色权限和指标格式化。
- 浏览器联调：市场加载、详情加载、上传校验错误、安装请求反馈、统计展示、后端停止时错误态。

## 验收标准

- 前端启动后，市场数据来自 `GET /api/v1/skills`，不读取运行时 mock catalog。
- 点击卡片能够通过 `GET /api/v1/skills/{id}` 加载详情。
- 上传合法示例 ZIP 返回 `201` 和 `validated`；非法 ZIP 返回字段级错误。
- 安装按钮返回可通过 M0 Schema 校验的安装清单。
- 调用事件重复提交不会重复计数。
- 统计页的数据来自后端聚合接口。
- 后端单测、前端测试、构建和浏览器联调均通过。
