# M4.3 生命周期与个人中心实施计划

> **执行约束：** 本计划必须按 `superpowers:executing-plans` 执行；每个任务先补失败测试，再写最小实现，最后运行该任务的验证命令。当前工作区不是 Git 仓库，不执行 commit、reset 或分支操作。

**目标：** 在 M4.1 分发与 M4.2 统计聚合之上，补齐 Skill 版本生命周期控制、下架拦截、影响面查询，以及绑定当前用户的安装、我的 Skill、收藏和调用历史页面。

**架构：** `GovernanceStore` 持久化版本生命周期元数据与收藏快照；`VersionLifecycleService` 负责状态机、权限、影响面和审计；目录、授权、Manifest、制品下载统一读取版本状态；`PersonalCenterService` 负责 actor.userId 范围内的聚合查询；Controller 只做参数解析和 `ApiResponse` 映射；前端通过 `skillApi.js` 调用真实接口，保留现有 M0-M4.2 API 兼容性。

**技术栈：** Java 17、Spring Boot、JUnit/MockMvc、Jackson、本地 JSON 快照；React、Vite、Node 内置测试、现有 CSS 变量和组件风格。

## 全局验收约束

- 生命周期只允许 `published -> deprecated`、`published -> withdrawn`、`deprecated -> withdrawn`；重复或逆向迁移返回 `VERSION_STATE_CONFLICT`，`withdrawn` 不可恢复。
- 迁移原因必填；替代版本只能是同一 Skill 的另一条 `published/deprecated` 版本。
- 只有 `admin` 可写生命周期；`admin/reviewer` 可查询全局影响面，`maintainer` 只能查询自己负责的 Skill。
- `deprecated` 在市场和详情可见并显示告警；新授权、Manifest、制品下载仍允许，但携带原因与替代版本；`withdrawn` 在普通市场隐藏，并拒绝新的授权、Manifest、制品下载（HTTP 410）。历史安装、调用、审核和审计记录保留。
- `/api/v1/me/*` 始终使用 `ActorResolver` 得到的 `actor.userId`，不得通过 query 参数查看他人数据；调用历史不得返回 session、prompt、output、deviceId、token 等敏感字段。
- 保留 `SkillVersion`、`GovernanceSnapshot`、`InstallManifest`、现有 API 的旧构造函数和可选字段，以兼容旧 JSON 快照及 M0-M4.2 客户端。

---

## 任务 1：扩展治理模型、快照与收藏原子操作

**文件：**

- 修改 `apps/api/src/main/java/com/huawei/skillcenter/governance/SkillVersion.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceSnapshot.java`
- 修改 `apps/api/src/main/java/com/huawei/skillcenter/governance/GovernanceStore.java`
- 新增 `apps/api/src/main/java/com/huawei/skillcenter/governance/FavoriteRecord.java`
- 修改/新增 `apps/api/src/test/java/com/huawei/skillcenter/governance/*Test.java`

**实现要点：**

1. 给 `SkillVersion` 增加 `statusReason`、`replacementVersion`、`statusChangedBy`、`statusChangedAt`；保留当前 12 参数构造函数并将新增字段默认为 `null`。
2. 给 `GovernanceSnapshot` 增加 `favorites`，新增带 favorites 的 canonical/兼容构造函数；旧 JSON 缺列时按空列表读取。所有 `GovernanceStore` 重建快照的路径都必须原样传递 favorites。
3. 增加 `FavoriteRecord(userId, skillId, createdAt)`；实现 `GovernanceStore.addFavorite`、`removeFavorite`、`favoritesForUser`，添加/删除均幂等，并通过既有 `addAudit` 记录 `FAVORITE_ADDED/FAVORITE_REMOVED`。
4. 增加 `GovernanceStore.updateVersion` 原子替换方法，禁止 Controller 直接改快照；更新时保持 reviews/installations/audits/authorizations/favorites 不丢失。

**先写测试：**

- 旧 12 参数 `SkillVersion` 和旧四/五字段快照可构造，新增字段为空。
- 更新版本状态后所有其他快照集合保持不变；JSON 重载后 lifecycle 字段仍存在。
- 同一用户重复收藏/重复取消不会生成重复记录，跨用户记录相互隔离。

**验证：** `mvn -B -q -f apps/api/pom.xml -Dtest=GovernanceStoreTest,SkillVersionTest test`

## 任务 2：实现版本生命周期服务、影响面和管理 API

**文件：**

- 新增 `apps/api/src/main/java/com/huawei/skillcenter/governance/VersionLifecycleService.java`
- 新增 `VersionLifecycleController.java`、`VersionLifecycleRequest.java`、`VersionImpact.java`
- 新增 `SkillVersionNotFoundException.java`、`VersionStateConflictException.java`、`WithdrawnVersionUnavailableException.java`
- 修改 `GlobalExceptionHandler.java`
- 新增 `apps/api/src/test/java/com/huawei/skillcenter/governance/VersionLifecycleServiceTest.java`
- 新增 `apps/api/src/test/java/com/huawei/skillcenter/governance/VersionLifecycleControllerTest.java`

**实现要点：**

1. `VersionLifecycleService.deprecate/withdraw(skillId, version, request, actor, requestId)` 统一校验路径、Skill/版本存在性、角色、状态迁移矩阵、原因和替代版本；成功后写入元数据、`VERSION_DEPRECATED`/`VERSION_WITHDRAWN` 审计及操作人/时间。
2. `impact(skillId, version, actor)` 聚合 `InstallationRecord` 全历史、active 安装、去重用户/团队、clientType 数量，以及 `InvocationEventService.events()` 中该版本的调用数和活跃调用用户；返回 replacementVersion。进程重启后调用统计自然为当前进程可见值。
3. Controller 提供 `POST /api/v1/skills/{skillId}/versions/{version}/deprecate`、`POST .../withdraw`、`GET .../impact`，沿用 `ApiResponse`、`requestId` 和 `X-User-*` 角色头。
4. 错误映射：缺参数/替代版本非法为 400 `INVALID_REQUEST`；角色不足为 403；不存在为 404 `SKILL_VERSION_NOT_FOUND`；非法迁移为 409 `VERSION_STATE_CONFLICT`；下架后新分发为 410 `WITHDRAWN_VERSION_UNAVAILABLE`。

**先写测试：**

- 覆盖完整迁移矩阵、重复迁移、withdrawn 不可恢复、原因必填、替代版本跨 Skill/不存在/自身版本拒绝。
- admin 成功，reviewer/maintainer/普通用户写操作均 403；impact 的 admin/reviewer/所属 maintainer 权限和越权 404/403 明确。
- 影响面准确统计 installed/installing、历史失败/移除、去重 user/team、client types、当前进程 invocation。
- MockMvc 验证错误 envelope、requestId 和三个路径。

**验证：** `mvn -B -q -f apps/api/pom.xml -Dtest=VersionLifecycleServiceTest,VersionLifecycleControllerTest test`

## 任务 3：让目录、版本历史、授权、Manifest、制品下载遵守生命周期

**文件：**

- 修改 `SkillCatalogService.java`、`VersionHistoryController.java`
- 修改 `DistributionService.java`、`DistributionAuthorizationService.java`
- 修改 `ArtifactController.java`、`ArtifactDownloadService.java`
- 修改 `InstallManifest.java`（新增可选 lifecycle notice，保留旧构造函数）
- 新增/修改 `SkillCatalogLifecycleTest.java`、`DistributionLifecycleTest.java`、`ArtifactLifecycleTest.java`

**实现要点：**

1. 目录合并治理版本时保留 `published/deprecated`，隐藏 withdrawn；种子记录也要能被同 skill/version 的 Governance 状态覆盖，上传 ZIP 读取时不再硬编码 published。
2. 版本历史普通用户显示 published/deprecated，admin/reviewer 额外显示 withdrawn；维护现有角色过滤和字段兼容。
3. `DistributionService.createManifest` 和授权流程在最终版本解析处再次检查状态；withdrawn 抛 410；deprecated 允许分发并写入 reason/replacement lifecycle notice。
4. `ArtifactController` 在读取制品前重新查询 Governance 状态，避免状态变更后旧 URL 继续下载；返回统一 410 错误。

**先写测试：**

- deprecated 在市场/详情/版本历史可见，withdrawn 不在普通市场出现；管理员历史可见 withdrawn。
- deprecated Manifest 含生命周期告警；withdrawn 授权、Manifest、下载均 410。
- 状态迁移后旧制品 URL 立即失效；既有 InstallationRecord 和历史查询仍可读。

**验证：** `mvn -B -q -f apps/api/pom.xml -Dtest=SkillCatalogLifecycleTest,DistributionLifecycleTest,ArtifactLifecycleTest test`

## 任务 4：实现个人中心后端 API

**文件：**

- 新增 `apps/api/src/main/java/com/huawei/skillcenter/personal/PersonalCenterService.java`
- 新增 `PersonalCenterController.java`、`FavoriteView.java`、`InvocationHistoryView.java`、`PersonalCenterQuery.java`
- 修改 `InstallationService.java`（复用过滤逻辑但强制 actor.userId）
- 新增 `apps/api/src/test/java/com/huawei/skillcenter/personal/PersonalCenterServiceTest.java`
- 新增 `PersonalCenterControllerTest.java`

**接口：**

- `GET /api/v1/me/installations?status=&skillId=&clientType=`
- `GET /api/v1/me/skills`
- `GET/PUT/DELETE /api/v1/me/favorites[/{skillId}]`
- `GET /api/v1/me/invocations?from=&to=&skillId=&status=&page=&pageSize=`

**实现要点：**

1. 安装记录复用现有权限过滤，但始终只返回 `requestedBy == actor.userId`；支持 status/skillId/clientType。
2. “我的 Skill”按 Skill owner/team 和治理版本 uploadedBy 关联当前 actor，按 skillId 去重，返回最新版本和生命周期状态。
3. 收藏校验 Skill 存在；收藏列表保留 withdrawn 摘要并标记 withdrawn；PUT/DELETE 幂等，写审计。
4. 调用历史只过滤 `InvocationEvent.subject.userId`，按 occurredAt 倒序，支持时间/skill/status 过滤和 page/pageSize；返回安全字段及 total/page/pageSize。

**先写测试：**

- 普通用户无法通过 userId/query 参数读取他人安装或调用；admin 的 `/me` 仍只返回自己。
- owner/team/uploadedBy 的 Skill 去重准确；收藏新增/删除/重复请求行为稳定；withdrawn 收藏仍能展示。
- 调用历史分页、边界日期、状态过滤准确，响应不含敏感字段。
- Controller 验证参数错误、空数据、角色头缺失和 envelope。

**验证：** `mvn -B -q -f apps/api/pom.xml -Dtest=PersonalCenterServiceTest,PersonalCenterControllerTest test`

## 任务 5：接入真实前端 API 与生命周期/个人中心界面

**文件：**

- 修改 `apps/web/src/api/skillApi.js`
- 修改 `apps/web/src/App.jsx`、`apps/web/src/styles.css`
- 新增 `apps/web/src/personalCenter.js`（纯函数：分页、状态标签、收藏回滚、空/加载/错误状态）
- 新增/修改 `apps/web/tests/api-client.test.mjs`、`personal-center.test.mjs`、`state.test.mjs`

**实现要点：**

1. API client 增加 lifecycle deprecate/withdraw/impact、me installations/skills/favorites/invocations 方法；统一传递当前角色和 userId 头，解析 `ApiResponse` 错误码。
2. Detail/VersionHistory 展示状态、原因、替代版本；admin 提供必填原因的废弃/下架弹窗，下架前展示 impact；withdrawn 禁止安装，deprecated 显示告警且保留安装。
3. Detail 收藏按钮接 PUT/DELETE，失败回滚并 toast；Sidebar 的 Favorites、My Skills、Invocations 从 PlaceholderView 替换为真实列表，安装记录增加 status/Skill/clientType 过滤。
4. 所有页面具备 loading、空数据、接口错误、withdrawn/deprecated 状态；不改变现有市场、审核、统计页面和视觉变量。

**先写测试：**

- API client 路径、query、headers、错误码映射正确。
- 收藏 optimistic update 成功/失败回滚；生命周期弹窗拒绝空原因并正确提交 replacementVersion。
- 个人中心列表分页、筛选、空/错误态和 withdrawn 标签可渲染；旧 analytics/market/install 流程回归通过。

**验证：** `npm.cmd test --prefix apps/web`，随后 `npm.cmd run build --prefix apps/web`

## 任务 6：文档、联调冒烟与阶段验收

**文件：**

- 新增 `docs/project/M4.3-lifecycle-personal-center-status.md`
- 更新 `docs/project/M4.2-analytics-aggregation-status.md`，补充 M4.3 的接口边界链接
- 必要时更新 `docs/project/roadmap.md` 的详细阶段计数和完成状态

**验收顺序：**

1. `mvn -B -q -f apps/api/pom.xml test`
2. `npm.cmd test --prefix apps/web`
3. `npm.cmd run build --prefix apps/web`
4. 启动 API 后用同一 Skill 的测试版本执行：发布→废弃（验证 deprecated 市场/Manifest）、废弃→下架（验证 impact 和 410 授权/Manifest/下载）；再调用 `/api/v1/me/installations`、`/me/skills`、`/me/favorites`、`/me/invocations` 验证 actor 隔离、分页和空态。
5. 检查旧 analytics、审核、安装详情、artifact 路径回归；记录命令、响应摘要、已知本地进程数据边界（调用影响面仅统计当前进程）。

**完成标准：** 任务 1-5 的专项测试与全量测试均通过，前端生产构建成功，真实 API 冒烟覆盖生命周期和全部 `/me` 路径，状态文档列出已完成项、后续可替换点和未纳入范围（SSO、数据库、通知、在线编辑）。
