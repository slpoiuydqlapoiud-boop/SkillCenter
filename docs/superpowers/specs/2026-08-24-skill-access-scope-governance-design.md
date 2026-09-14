# Skill 维护资格与可见范围治理设计

## 目标

将 Skill 的“谁可以看、谁可以维护、谁可以提交新版本”从分散的角色判断提升为可持久化、可审计、可复用的资产治理规则，完成企业 Skill 平台路线图中的 IAM-007/IAM-008 基线。

本阶段只建设平台内的范围治理和统一授权判定，不实现真实 SSO/JWT、部门目录同步或机器凭证签发；身份来源继续使用现有 `ActorResolver`，未来只替换身份/团队解析端口。

## 当前缺口

- `TeamDefinition`、`RoleBinding` 已能表达团队和成员，但没有按 `skillId` 保存 ownerTeam、维护者集合和可见范围。
- 市场、详情、内容、安装、关系、生命周期和发布入口的授权逻辑不统一，存在按角色放行而忽略 Skill 归属的路径。
- `VersionLifecycleService` 中已有局部维护者判断，依赖目录回退且不能复用于上传、关系、发布和分发。
- 历史 Skill 没有范围记录；直接改为严格团队隔离会隐藏既有资产并破坏兼容性。

## 设计

### 1. Skill 范围资产

新增独立 `SkillScopeStore`，使用 JSON 原子替换保存 `SkillScope`：

```text
skillId
visibility: PUBLIC | TEAM | RESTRICTED
ownerTeamId
maintainerUserIds[]
revision
declaredBy / declaredAt
updatedBy / updatedAt
```

约束：

- `skillId` 为稳定主键；同一 Skill 只有一条当前范围记录。
- `TEAM` 必须引用活动团队；`RESTRICTED` 必须至少有一个维护者用户；`PUBLIC` 可以没有 ownerTeam，但仍可配置维护者。
- 维护者集合去空、去重、稳定排序；不保存密码、Token、Prompt、输入输出或团队成员以外的敏感数据。
- 更新以 `revision` 做乐观并发检查；旧 revision 不得覆盖新规则。
- 范围更新只改变访问/维护判定，不改变 Skill 版本、审核、质量门禁或发布批次状态。

### 2. 统一授权服务

新增 `SkillAuthorizationService`，所有业务入口只调用以下语义方法，不自行组合角色与 owner 条件：

```java
SkillScope effectiveScope(String skillId);
void requireVisible(String skillId, Actor actor, SkillVisibilityContext context);
void requireManage(String skillId, Actor actor);
void requireSubmitVersion(String skillId, Actor actor);
List<String> visibleSkillIds(Actor actor);
SkillScope updateScope(String skillId, SkillScopeMutation mutation, Actor actor, String requestId);
```

`SkillVisibilityContext` 只描述调用场景，不改变范围模型：`CATALOG` 用于市场列表/详情，`CONTENT` 用于 Skill 内容读取，`DISTRIBUTION` 用于安装授权/Manifest/制品，`GOVERNANCE` 用于审核、生命周期、关系和发布治理读取。上下文只决定版本状态过滤和隐藏策略，团队/维护者判定始终由同一授权服务完成。

判定矩阵：

| Actor | PUBLIC | TEAM | RESTRICTED | 管理/提交新版本 |
| --- | --- | --- | --- | --- |
| developer/viewer | 可见已发布/已废弃 | 所属团队成员可见 | 明确维护者可见 | 否 |
| maintainer | 可见 | 所属团队或维护者可见 | 明确维护者可见 | 仅自己负责的 Skill |
| reviewer | 可读治理资产 | 可读治理资产 | 可读治理资产 | 否 |
| admin | 可见 | 可见 | 可见 | 是 |

`pending_review`、`security_review`、`rejected` 和 `withdrawn` 版本不向普通开发者泄露；审核/审计角色可按既有职责读取治理状态。对普通用户隐藏的 Skill 统一表现为稳定的不可见/不存在语义，不回显范围记录或 owner 信息。

历史兼容策略：

- 没有 `SkillScope` 的既有 Skill 按 `PUBLIC` 有效范围处理，维持当前市场、详情、安装和下载行为。
- 缺少显式维护者时，以最新非下架版本的 `uploadedBy` 作为管理回退主体；该回退只用于维护判定，不写回或暴露为新的范围记录。
- 首次管理员更新范围时创建 revision=1 的显式记录；新 Skill 首次提交后可由管理员补齐范围，默认仍为 PUBLIC。

### 3. 入口接入边界

将授权服务接入以下路径：

- `SkillCatalogService` 的列表、详情和内容：列表按 actor 过滤，详情/内容先做可见性检查。
- 上传/审核提交：新 Skill 首次提交允许有效 developer/maintainer 或管理员；已有 Skill 新版本要求 `requireSubmitVersion`；管理员可代管。
- 版本生命周期、受控发布和关系创建/查询/影响分析：管理员全量；维护者只能操作或查看其负责的 Skill；审核员保持治理只读。
- 安装授权、Manifest 和制品下载：在现有发布准入之前先校验 Skill 可见范围，拒绝不可见资产且不发放 Token。
- 统计/运营保持现有管理员边界；个人中心只返回当前 actor 已授权的个人记录。

不把范围治理接入质量分数计算，不因范围更新自动触发评测、发布、回滚或通知业务用户。

### 4. 管理 API 与 Web

新增管理员/维护者可读、管理员可写的接口：

```text
GET /api/v1/admin/skill-access/scopes?skillId=
PUT /api/v1/admin/skill-access/scopes/{skillId}
     body: { visibility, ownerTeamId, maintainerUserIds, revision }
```

响应只返回范围、团队 ID、维护者用户 ID、revision 和更新时间，不返回团队成员详情、凭据或业务正文。写接口返回稳定错误码：

- `SKILL_SCOPE_NOT_FOUND`
- `SKILL_SCOPE_CONFLICT`
- `SKILL_SCOPE_INVALID`
- `SKILL_NOT_VISIBLE`
- `SKILL_MANAGE_FORBIDDEN`

Skill 详情管理员面板展示当前范围、维护团队和维护者列表；普通开发者看不到治理字段。页面加载、打开详情和切换范围只读；保存必须由管理员明确提交，并显示 revision 冲突和审计结果。

### 5. 审计、恢复和安全

- 范围创建/更新写入 `SKILL_SCOPE_CREATED`/`SKILL_SCOPE_UPDATED`，仅包含 Skill ID、可见范围、ownerTeamId、维护者数量、revision 和稳定原因码。
- Store 启动恢复拒绝重复 Skill ID、非法可见范围、空的 RESTRICTED 维护者集和 revision 回退；活动团队引用由读取治理配置的授权服务在写入和使用前 fail-closed 校验，保持 Store 与团队目录解耦。
- API 不返回 scope Store 的异常原因；内部异常收敛为 `SKILL_SCOPE_PERSISTENCE_FAILED`。
- 权限检查在服务端业务入口执行，前端隐藏菜单不作为安全边界。
- 范围规则变更不覆盖既有 SkillVersion 内容，也不写入 Artifact、Trace、调用正文或外部 Provider 数据。

## 可验证结果

1. 范围记录可原子保存、重启恢复、revision 冲突拒绝，非法团队/维护者配置不会落盘。
2. PUBLIC、TEAM、RESTRICTED 在开发者、维护者、审核员和管理员角色下拥有稳定且可测试的可见/管理结果。
3. 市场、详情、内容、上传、生命周期、发布、关系和分发使用同一授权服务，不存在仅因角色即可越权的入口。
4. 历史无范围记录的 Skill 保持可见和可分发，首次显式治理后才进入严格范围控制。
5. 范围变化不会改变质量门禁、发布状态或版本内容，且审计不泄露业务正文和凭据。
6. Web 管理面板支持显式保存、空态、权限隐藏和 revision 冲突提示；API/Web 全量回归通过。
