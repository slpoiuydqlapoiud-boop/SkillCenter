# M5.1 平台治理配置设计

日期：2026-08-18

## 1. 目标

在 M4.3 生命周期与个人中心能力之上，补齐 Skill 平台的治理配置工作台：管理员可以维护团队、角色绑定、分类、标签、合集和平台策略；这些配置通过真实后端接口持久化、审计并驱动市场筛选、合集展示和分发约束。Skill 正文和包内容仍只能通过本地 ZIP 上传，网站不创建或编辑 Skill。

本阶段继续采用本地可运行、契约可替换的实现：配置写入现有 `GovernanceStore` JSON 快照；后续接入 PostgreSQL、SSO、组织目录或配置中心时，保持 API 路径、错误码和字段语义不变。

## 2. 范围与非目标

### 2.1 本阶段范围

- 团队定义：名称、编码、描述、状态、成员 userId 和负责人。
- 角色绑定：userId 到平台角色与团队的本地绑定、状态和变更审计。
- 分类与标签字典：编码、显示名、描述、排序、启用状态；停用项不再用于新配置，但历史 Skill 元数据不被删除。
- 合集：名称、描述、负责人团队、可见性、排序、Skill ID 成员列表；成员增删幂等。
- 平台策略：市场分页上限、允许的分页选项、客户端最低协议版本、默认合集可见性和策略版本。
- 管理 API、普通读取 API、前端治理配置工作台和市场/合集读取联动。
- 关键配置变更的追加审计：操作者、角色、对象、动作、变更前后摘要、原因、请求 ID、时间。
- 旧治理 JSON 快照兼容：缺少新增字段时按空配置和默认策略读取，不覆盖既有版本、安装、授权、收藏和审计。

### 2.2 非目标

- 不接入真实 Huawei SSO、LDAP、组织目录或生产级身份认证；`X-User-Id/X-User-Role` 继续是本地开发适配器。
- 不在网站内编辑 Skill 正文、`SKILL.md`、`skill.json` 或重新打包 ZIP。
- 不实现跨团队审批流、自动角色继承、复杂 ABAC、外部消息通知和策略灰度发布。
- 不在本阶段引入数据库、消息队列、配置中心或多实例一致性协议。
- 不删除历史分类、标签、合集成员、审计或 Skill 记录；停用通过状态表达。

## 3. 领域模型与持久化

### 3.1 新增模型

```text
TeamDefinition
  teamId: String              // 稳定编码，^[a-z0-9][a-z0-9-_-]{1,63}$
  name: String                // 1–80 字符
  description: String?
  ownerUserId: String
  memberUserIds: List<String>
  status: active|inactive
  createdAt, updatedAt: Instant

RoleBinding
  userId: String
  role: viewer|maintainer|reviewer|admin
  teamId: String?
  status: active|inactive
  changedBy: String
  changedAt: Instant

CategoryDefinition / TagDefinition
  code: String                // 稳定编码，不能与已有启用项冲突
  displayName: String         // 1–40 字符
  description: String?
  sortOrder: int              // >= 0
  status: active|inactive
  updatedBy: String
  updatedAt: Instant

CollectionDefinition
  collectionId: String
  name: String                // 1–80 字符
  description: String?
  ownerTeamId: String?
  visibility: public|team
  skillIds: List<String>
  sortOrder: int
  status: active|inactive
  updatedBy: String
  updatedAt: Instant

PlatformPolicy
  policyVersion: int
  pageSizeOptions: List<int>  // 1–100，去重、升序
  maxPageSize: int            // 必须 >= max(pageSizeOptions)
  minimumClientVersion: String
  defaultCollectionVisibility: public|team
  updatedBy: String
  updatedAt: Instant
```

`GovernanceSnapshot` 增加 `teams`、`roleBindings`、`categories`、`tags`、`collections`、`platformPolicy` 六个字段。canonical 构造函数保持新增字段可为 null；旧 4/5/6 参数兼容构造函数继续保留。读取旧 JSON 时默认：空列表、`pageSizeOptions=[12,24,48]`、`maxPageSize=48`、`minimumClientVersion="1.0.0"`、`defaultCollectionVisibility="public"`、`policyVersion=1`。

### 3.2 约束与一致性

- 同一列表中的稳定编码、团队编码、合集 ID 唯一且大小写不敏感。
- DELETE 接口统一采用幂等停用语义：已有 Skill、历史审计和合集引用保留；M5.1 不提供物理删除，避免配置 ID 被复用造成历史歧义。
- 团队停用前不能作为新合集 ownerTeam；角色绑定指向 inactive 团队时允许保留历史但不能新增成员。
- `CollectionDefinition.skillIds` 自动去重；加入不存在 Skill 返回 `SKILL_NOT_FOUND`，加入 withdrawn Skill 返回 `INVALID_REQUEST` 并保留已有成员。
- 策略更新必须保证分页选项和上限合法，客户端最低版本使用 SemVer；策略版本每次成功写入递增 1。
- 所有写操作在单次 `GovernanceStore.mutate` 中完成，审计和配置更新要么同时成功，要么都不落盘。

## 4. API 设计

### 4.1 管理 API

所有管理写接口要求 `X-User-Role=admin`，缺少原因的变更返回 `400 INVALID_REQUEST`；重复编码返回 `409 CONFIG_CONFLICT`；请求响应沿用 `ApiResponse` 和 `requestId`。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/admin/teams` | 分页查询团队，支持 `status` |
| POST | `/api/v1/admin/teams` | 创建团队 |
| PUT | `/api/v1/admin/teams/{teamId}` | 更新团队基本信息/成员/状态 |
| DELETE | `/api/v1/admin/teams/{teamId}` | 幂等停用团队 |
| GET | `/api/v1/admin/role-bindings` | 按 userId/teamId/status 查询绑定 |
| PUT | `/api/v1/admin/role-bindings/{userId}` | 幂等设置角色和团队 |
| DELETE | `/api/v1/admin/role-bindings/{userId}` | 停用绑定 |
| GET/POST | `/api/v1/admin/taxonomy/categories` | 查询/创建分类 |
| PUT/DELETE | `/api/v1/admin/taxonomy/categories/{code}` | 更新/停用分类 |
| GET/POST | `/api/v1/admin/taxonomy/tags` | 查询/创建标签 |
| PUT/DELETE | `/api/v1/admin/taxonomy/tags/{code}` | 更新/停用标签 |
| GET/POST | `/api/v1/admin/collections` | 查询/创建合集 |
| GET/PUT/DELETE | `/api/v1/admin/collections/{collectionId}` | 查看/更新/停用合集 |
| PUT/DELETE | `/api/v1/admin/collections/{collectionId}/skills/{skillId}` | 幂等添加/移除合集成员 |
| GET | `/api/v1/admin/policies` | 读取当前策略 |
| PUT | `/api/v1/admin/policies` | 替换策略并递增版本 |

### 4.2 普通读取 API

- `GET /api/v1/governance/taxonomy` 返回启用分类、标签、公开合集和当前策略摘要，供市场/合集页面读取。
- `GET /api/v1/collections` 返回公开且 active 的合集，支持 `page/pageSize`，超出策略上限返回 `INVALID_REQUEST`。
- `GET /api/v1/collections/{collectionId}` 仅允许访问公开合集或当前用户所属团队合集；不存在、停用或越权统一返回 `COLLECTION_NOT_FOUND`。
- 市场 `GET /api/v1/skills` 的 category/status/risk 过滤继续兼容旧参数；category 过滤值若不是启用字典项时返回空结果而非 500。

## 5. 服务与数据流

新增 `GovernanceConfigurationService`，负责校验和更新所有治理配置；Controller 不直接操作快照。服务接口：

```java
GovernanceConfigurationSnapshot read(Actor actor);
TeamDefinition upsertTeam(TeamMutation request, Actor actor, String requestId);
void deactivateTeam(String teamId, Actor actor, String requestId);
RoleBinding upsertRoleBinding(String userId, RoleBindingMutation request, Actor actor, String requestId);
TaxonomyDefinition upsertCategory(TagOrCategoryMutation request, Actor actor, String requestId);
TaxonomyDefinition upsertTag(TagOrCategoryMutation request, Actor actor, String requestId);
CollectionDefinition upsertCollection(CollectionMutation request, Actor actor, String requestId);
CollectionDefinition addSkill(String collectionId, String skillId, Actor actor, String requestId);
CollectionDefinition removeSkill(String collectionId, String skillId, Actor actor, String requestId);
PlatformPolicy updatePolicy(PolicyMutation request, Actor actor, String requestId);
```

写入流程：`ActorResolver -> RoleGuard(admin) -> DTO 校验 -> 引用/唯一性校验 -> GovernanceStore.mutate -> AuditEvent -> ApiResponse`。

读取流程：`GovernanceStore.snapshot -> active/status/visibility 过滤 -> 角色/团队范围过滤 -> 分页/排序 -> ApiResponse`。

市场联动只消费启用字典和公开合集；Skill 内容仍以目录/ZIP 的元数据为来源，不将治理配置误写回 Skill 包。

## 6. 前端工作台

将现有设置/标签管理占位页替换为 `GovernanceSettingsView`：

- 团队：列表、创建/编辑、成员 userId 编辑、停用确认。
- 角色：按 userId 设置角色和团队，显示 active/inactive。
- 分类/标签：字典表格、排序、启停和重复冲突提示。
- 合集：创建合集、维护可见性/ownerTeam、添加或移除 Skill。
- 平台策略：分页选项、上限、客户端最低版本、默认合集可见性；保存前本地校验并展示 policyVersion。
- 所有表格具备 loading、空态、错误态、保存中、冲突回滚和成功 toast；非 admin 只读。
- 市场筛选从 `/api/v1/governance/taxonomy` 读取启用分类/标签；合集页面从 `/api/v1/collections` 读取真实数据。

## 7. 错误与审计

新增稳定错误码：

- `CONFIG_CONFLICT`：重复编码、并发版本冲突或非法状态切换。
- `COLLECTION_NOT_FOUND`：合集不存在、停用或当前主体无权访问。
- `SKILL_NOT_FOUND`：合集成员引用不存在 Skill。
- `INVALID_REQUEST`：字段、SemVer、分页策略、团队/角色引用不合法。
- `FORBIDDEN`：非 admin 写入治理配置，或用户访问非公开团队合集。

审计动作：`TEAM_CREATED/UPDATED/DEACTIVATED`、`ROLE_BINDING_UPDATED/DEACTIVATED`、`CATEGORY_CREATED/UPDATED/DEACTIVATED`、`TAG_CREATED/UPDATED/DEACTIVATED`、`COLLECTION_CREATED/UPDATED/DEACTIVATED`、`COLLECTION_SKILL_ADDED/REMOVED`、`PLATFORM_POLICY_UPDATED`。metadata 只记录对象 ID、变更摘要、原因和 requestId，不记录 Skill 正文、成员敏感信息或 token。

## 8. 测试与验收

### 8.1 后端

- 模型/快照兼容：旧 JSON 缺治理字段可加载；新字段重启后保留；任意治理 mutation 不丢版本、安装、授权、收藏和审计。
- 校验：重复编码、非法角色、inactive 团队引用、非法分页策略、非法 SemVer、重复合集成员均返回稳定错误。
- 权限：viewer/maintainer/reviewer 不能写；reviewer 可读字典；team visibility 越权返回 `COLLECTION_NOT_FOUND`。
- 联动：市场 taxonomy 读取启用项；合集成员添加/移除幂等；withdrawn Skill 不能新加入合集；停用字典不破坏历史 Skill。
- API：每个管理资源至少覆盖成功、校验失败、越权、冲突和重启恢复用例。

### 8.2 前端

- API client 路径、query、headers、错误码映射覆盖。
- 治理工作台 tab 切换、CRUD optimistic state、冲突回滚、非 admin 只读和空/加载/错误态覆盖。
- 市场筛选和合集页面回归；旧市场、审核、分发、统计页面保持通过。

### 8.3 验收命令

```text
mvn -B -q -f apps/api/pom.xml test
npm.cmd test --prefix apps/web
npm.cmd run build --prefix apps/web
```

额外执行 API smoke：创建团队/分类/标签/合集，添加 Skill，更新策略，重启 API 后读取；以 viewer、reviewer、admin 三种 actor 验证权限、公开/团队合集可见性和市场筛选。

## 9. 后续替换点

`GovernanceConfigurationService` 和 `GovernanceStore` 是本阶段的适配边界。后续接入企业 SSO/组织目录时替换 `ActorResolver` 与 `RoleBinding` 来源；接入 PostgreSQL 时替换快照存储；接入配置中心时替换 `PlatformPolicy` 来源；不改变 API 资源路径、错误码、审计动作和前端数据结构。
