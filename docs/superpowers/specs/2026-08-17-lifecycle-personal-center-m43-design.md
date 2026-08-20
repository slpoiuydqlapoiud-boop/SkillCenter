# M4.3 版本生命周期与个人中心设计

## 1. 目标

在 M4.1 分发授权、M4.2 统计聚合的基础上，补齐 Skill 版本生命周期控制和个人中心真实数据页面。管理员可以安全地废弃或下架已发布版本，平台能够立即阻断下架版本的新分发，同时保留历史安装与统计；普通用户可以查看自己的安装、负责的 Skill、收藏和调用历史。

本阶段继续采用本地可运行、契约可替换的实现：治理状态和收藏关系写入现有 `GovernanceStore` 快照，调用历史复用当前进程内 `InvocationEventService`，后续接入数据库、SSO 或通知中心时不改变资源路径和状态语义。

## 2. 范围与非目标

### 2.1 本阶段范围

- 版本状态 `published -> deprecated -> withdrawn`。
- 废弃/下架原因、替代版本、操作人和时间的持久化及审计。
- 版本影响面查询：当前安装、用户、团队、客户端和调用概览。
- 市场、详情、版本历史和分发链路对生命周期状态的一致展示与拦截。
- 个人中心真实页面和 API：我的安装、我的 Skill、我的收藏、我的调用历史。
- 收藏关系持久化、幂等新增和删除。
- 角色边界与敏感字段保护的自动化测试。

### 2.2 非目标

- 不在网站内创建或编辑 Skill；Skill 仍只能从本地 ZIP 上传。
- 不实现通知中心、邮件/IM 通知、审批编排或自动迁移替代版本。
- 不引入真实 SSO/JWT、数据库、消息队列或跨区域分析仓库。
- 不删除已有安装、调用事件或历史版本；`withdrawn` 只阻断新的授权、manifest 和制品下载。

## 3. 版本状态机与业务规则

### 3.1 状态

| 状态 | 市场/详情 | 新安装授权 | 制品下载 | 已有安装 |
| --- | --- | --- | --- | --- |
| `published` | 正常展示 | 允许 | 允许 | 正常使用 |
| `deprecated` | 展示警告、停止推荐 | 允许但返回替代版本和原因 | 允许 | 正常使用 |
| `withdrawn` | 市场隐藏，管理员历史可见 | 拒绝 | 拒绝 | 保留记录和历史统计 |

允许的迁移只有：

- `published -> deprecated`
- `published -> withdrawn`
- `deprecated -> withdrawn`

同一状态重复操作返回 `VERSION_STATE_CONFLICT`；`withdrawn` 不可恢复。所有迁移必须提供非空原因；废弃可以提供 `replacementVersion`，若提供必须是同一 Skill 的另一个已发布或废弃版本。

### 3.2 生命周期元数据

`SkillVersion` 增加：

- `statusReason`
- `replacementVersion`
- `statusChangedBy`
- `statusChangedAt`

旧快照缺失这些字段时按 `null` 读取，现有构造器保留兼容重载。版本视图增加同名字段，普通用户只看到公开的已发布/废弃版本，管理员可看到下架版本的原因和影响面。

## 4. API 设计

### 4.1 生命周期操作

废弃/下架写操作仅 `admin` 可执行；影响面查询允许 `admin/reviewer` 全局查看，`maintainer` 仅可查看自己负责 Skill 的版本。所有接口沿用 `X-User-Id`、`X-User-Role` 本地角色头。请求体：

```json
{
  "reason": "存在安全风险，迁移到 1.3.0",
  "replacementVersion": "1.3.0"
}
```

接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/skills/{skillId}/versions/{version}/deprecate` | 废弃版本 |
| `POST` | `/api/v1/skills/{skillId}/versions/{version}/withdraw` | 下架版本 |
| `GET` | `/api/v1/skills/{skillId}/versions/{version}/impact` | 查询影响面 |

成功响应保留 `ApiResponse` 包装，返回更新后的版本视图或影响面。失败错误：

- `400 INVALID_REQUEST`：缺少原因、替代版本格式非法或版本路径参数非法。
- `403 FORBIDDEN`：非管理员执行状态变更。
- `404 SKILL_VERSION_NOT_FOUND`：Skill/版本不存在或无权查看。
- `409 VERSION_STATE_CONFLICT`：非法状态迁移或重复操作。
- `410 WITHDRAWN_VERSION_UNAVAILABLE`：下架版本尝试新授权、manifest 或制品下载。

### 4.2 个人中心

所有接口先解析 Actor；“我的”语义始终绑定 `actor.userId`，不能通过查询参数查看他人数据。管理员也只能在管理统计接口查看全局，个人中心仍默认返回自己的数据。

| 方法 | 路径 | 查询参数/说明 |
| --- | --- | --- |
| `GET` | `/api/v1/me/installations` | `status`、`skillId`、`clientType` 可选；复用安装记录字段 |
| `GET` | `/api/v1/me/skills` | 返回当前用户负责或上传的已发布/废弃 Skill |
| `GET` | `/api/v1/me/favorites` | 返回收藏 Skill 摘要 |
| `PUT` | `/api/v1/me/favorites/{skillId}` | 幂等新增收藏 |
| `DELETE` | `/api/v1/me/favorites/{skillId}` | 幂等删除收藏 |
| `GET` | `/api/v1/me/invocations` | `from`、`to`、`skillId`、`status`、`page`、`pageSize` 可选 |

调用历史只返回 `occurredAt`、`skillId`、`version`、客户端类型/版本、状态、耗时和错误码；不返回 `sessionId`、提示词、输出、设备 ID、token 或其他正文。

## 5. 影响面与分发一致性

新增 `VersionLifecycleService`，集中负责状态迁移、版本查找、影响面和审计；`GovernanceStore` 提供原子更新版本的方法，避免 Controller 直接拼装快照。

影响面按版本精确匹配：

- `installationCount`：该 Skill/版本的安装记录数，包含已安装、安装中、失败和已移除历史。
- `activeInstallationCount`：状态为 `installed` 或 `installing` 的安装数。
- `userCount`、`teamCount`：安装记录中去重后的请求用户和团队。
- `clientTypes`：安装记录中的客户端类型及数量。
- `invocationCount`、`activeInvocationUsers`：M4.2 当前进程内调用事件的版本聚合；服务重启后按本地运行时边界显示 0 或当前可见值。
- `replacementVersion`：生命周期元数据中的替代版本，不自动迁移安装。

分发一致性要求：

1. `SkillCatalogService` 将 `deprecated` 版本映射为可见目录项并携带状态；`withdrawn` 不进入普通市场。
2. `DistributionService.createManifest` 和授权服务读取同一版本状态；`withdrawn` 直接抛出受控异常，`deprecated` 在 manifest 中返回原因和替代版本。
3. `ArtifactController` 下载前再次检查版本状态，避免状态变更后旧 URL 继续下载。
4. 版本历史对普通用户展示 `published/deprecated`，管理员额外展示 `withdrawn`。

## 6. 个人中心存储与服务

新增 `FavoriteRecord(userId, skillId, createdAt)`，存入 `GovernanceSnapshot.favorites`。为兼容已有 JSON 快照，`GovernanceSnapshot` 增加带默认空列表的构造器重载，所有既有变更方法必须保留 favorites。

新增 `PersonalCenterService`：

- `installations(actor, filters)` 复用现有安装权限并强制 `requestedBy=actor.userId`。
- `mySkills(actor)` 从 Skill 目录 owner/team 和治理版本 `uploadedBy` 派生，去重后按 Skill ID 排序。
- `favorites(actor)` 读取收藏记录并关联可见 Skill 摘要；已下架 Skill 保留收藏但显示“已下架”。
- `addFavorite/removeFavorite(actor, skillId)` 校验 Skill 存在，重复写入和重复删除均成功返回当前状态。
- `invocations(actor, filters)` 从 `InvocationEventService.events()` 过滤主体用户 ID，按发生时间倒序分页。

收藏、安装和调用查询不写入敏感内容；收藏写操作进入审计日志，调用查询不产生新的审计正文。

## 7. 前端交互

### 7.1 版本生命周期

- 版本历史增加状态、原因、替代版本和管理员操作入口。
- 管理员点击“废弃/下架”打开确认弹窗，原因必填；废弃可选替代版本，下架显示影响面摘要。
- Skill 详情和市场卡片对 `deprecated` 显示警告；`withdrawn` 不出现在普通市场。
- 下架版本的安装按钮禁用并显示“版本已下架”；已有安装记录仍可查看。

### 7.2 个人中心

- “我的收藏”展示收藏卡片，支持取消收藏和打开详情。
- “我创建的”展示当前用户负责/上传的 Skill、最新版本和生命周期状态；管理员不把全局目录误显示为个人列表。
- “安装记录”增加状态、Skill、客户端筛选，并保留失败原因和重新安装入口（重新安装仍走新授权）。
- “调用历史”展示时间、Skill、版本、状态、耗时和错误码，空数据和本地运行时提示清晰。
- Skill 详情收藏按钮通过 PUT/DELETE 接口保持实时状态，失败时恢复按钮状态并显示 toast。

## 8. 错误、审计与兼容

- 所有新增错误沿用现有 error envelope 和 `requestId`。
- 生命周期审计动作：`VERSION_DEPRECATED`、`VERSION_WITHDRAWN`、`FAVORITE_ADDED`、`FAVORITE_REMOVED`；元数据只包含 Skill ID、版本、原因摘要、替代版本和请求 ID，不记录调用正文。
- M0-M4.2 API 路径和响应字段保持兼容；新增字段允许旧客户端忽略。
- 旧 Governance JSON 快照启动时自动补空 favorites 和生命周期元数据，不覆盖已有状态。

## 9. 测试与验收

### 9.1 后端

- 状态迁移允许/拒绝矩阵覆盖，重复操作返回 `VERSION_STATE_CONFLICT`。
- 废弃版本允许 manifest 并返回原因/替代版本；下架版本新授权、manifest、制品下载均返回 `WITHDRAWN_VERSION_UNAVAILABLE`。
- 影响面准确统计安装、活跃安装、用户、团队、客户端和当前进程调用。
- 普通用户无法查看他人安装/调用；非管理员不能执行生命周期写操作。
- 收藏新增/删除幂等，重启后快照仍保留收藏。
- 我的 Skill 去重且只返回 owner/team/uploadedBy 匹配的数据；调用历史不含敏感字段。

### 9.2 前端

- 生命周期弹窗校验原因并展示状态冲突/下架影响面错误。
- 收藏按钮 PUT/DELETE 状态切换和失败回滚正确。
- 四个个人中心页面在空数据、加载、权限错误和下架 Skill 状态下均有明确 UI。
- 旧统计、市场、分发和审核页面回归通过。

### 9.3 验收命令

```text
mvn -B -q -f apps/api/pom.xml test
npm.cmd test --prefix apps/web
npm.cmd run build --prefix apps/web
```

另需执行 lifecycle 与 `/api/v1/me/*` 的 MockMvc smoke，以及下架后新授权/下载被拒绝的真实 API smoke。

## 10. 后续替换点

本阶段的本地收藏和事件查询可替换为关系型数据库/分析仓库适配器；生命周期状态和客户端错误码不变。通知中心、自动替代安装、跨团队授权和生产级审计保留到后续上线候选阶段。
