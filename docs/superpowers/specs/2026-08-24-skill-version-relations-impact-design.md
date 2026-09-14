# Skill 版本关系与影响分析设计

## 目标

将 Skill 之间的复用、组合和替代关系沉淀为可审计的版本资产，使管理员在发布、废弃或下架某个版本前能够看到受影响的下游 Skill、其生命周期状态、生产晋级状态和活跃安装规模。

本阶段建立关系事实和只读影响分析，不自动阻断发布/下架，不推断 Prompt、输入输出或工具参数，也不连接外部 Agent Runtime、MCP、LLM 或 CD 系统。

## 当前缺口

- `VersionLifecycleService.impact` 只统计目标版本自身的安装和调用，不能识别下游 Skill 的依赖关系。
- 现有 `SkillVersion` 记录版本内容和生命周期状态，但没有版本到版本的复用/组合关系。
- 发布控制面能判断单个版本的生产晋级，不能回答某个版本变更会影响哪些下游资产。
- 直接把关系数据接入发布门禁会在关系资产尚未完整迁移时产生误阻断，因此先提供事实查询和审计。

## 设计

### 1. 关系模型

新增 `SkillRelation`，关系两端均绑定具体 `skillId + version`，而不是只绑定 Skill ID：

- `DEPENDS_ON`：源版本运行或编排依赖目标版本；目标变更会影响源版本。
- `COMPOSES`：源版本组合调用目标版本；目标变更会影响源版本。
- `REPLACES`：源版本声明替代目标版本；用于迁移路径展示，不作为运行依赖执行。

关系记录包含关系 ID、源/目标 Skill 与版本、类型、`ACTIVE/RETIRED` 状态、声明人/时间、撤销人/时间和稳定原因码。关系上下文创建后不可变；撤销通过状态迁移保留历史，不物理删除。

同一源版本、目标版本和关系类型只能存在一个 ACTIVE 关系；源目标不能相同；关系图中不得形成环。关系创建要求两端版本存在且未 `withdrawn`，允许 `pending_review` 版本提前声明关系以支持新版本协作。

### 2. 存储和服务边界

新增独立 JSON 原子存储 `SkillRelationStore`，不修改 `GovernanceSnapshot` 结构，避免破坏已有治理快照兼容性。Store 启动时校验关系 ID 唯一、复合业务键唯一、状态字段配对和图中无环；写入使用临时文件替换。

`SkillRelationService` 负责：

- `create(SkillRelationRequest, Actor, requestId)`：角色、版本存在性、类型、重复关系和环依赖校验后创建关系并写审计。
- `retire(String relationId, String reason, Actor, requestId)`：管理员撤销 ACTIVE 关系，幂等重复撤销返回稳定冲突。
- `list(RelationQuery, Actor)`：按源/目标 Skill、版本和状态查询，结果稳定排序。
- `impact(String skillId, String version, ImpactQuery, Actor)`：沿 ACTIVE 关系的反向边计算下游影响，返回受影响版本的去重结果。

影响分析默认最多 5 层、100 个节点；调用方可降低上限但不能超过服务端硬上限 10 层、500 个节点。超限不失败，返回 `truncated=true` 和稳定的节点截断结果。遍历结果按最短深度、Skill ID、版本和关系 ID 排序，保证重启和不同写入顺序下结果一致。

### 3. 影响结果

`SkillRelationImpact` 只包含：

- 根 Skill/版本、查询上限、`truncated`；
- 下游受影响版本的 Skill ID、版本、关系类型、最短深度、当前生命周期状态；
- 每个下游版本是否存在匹配 SHA-256 的 PRODUCTION `PROMOTED` ReleaseRecord；
- 当前 `installed/installing` 安装数量。

不返回安装用户、团队成员、授权 Token、调用正文、Prompt、Trace、工具参数或 Provider 异常正文。关系列表与影响分析均为管理员、审核员和维护者可见；本阶段维护者查询范围与其余治理角色一致，后续接入团队归属后再收窄为本人维护的版本。

### 4. API 和 Web

新增管理员接口：

```text
POST /api/v1/admin/skill-relations
GET  /api/v1/admin/skill-relations?sourceSkillId=&sourceVersion=&targetSkillId=&targetVersion=&status=
POST /api/v1/admin/skill-relations/{relationId}/retire
GET  /api/v1/admin/skill-relations/impact?skillId=&version=&maxDepth=&maxNodes=
```

Skill 详情的版本历史在管理员打开生命周期操作时加载关系影响摘要，展示受影响数量、截断状态、关系类型、版本状态、生产晋级状态和活跃安装数；页面加载与打开详情只读，不自动创建或撤销关系。

### 5. 安全、兼容和后续演进

- API 仅使用稳定错误码，如 `SKILL_RELATION_CONFLICT`、`SKILL_RELATION_CYCLE`、`SKILL_RELATION_VERSION_NOT_FOUND`、`SKILL_RELATION_LIMIT_INVALID`。
- 关系创建、撤销和影响查询均写入最小化审计元数据；不保存业务正文。
- 没有关系记录的现有 Skill 返回空影响集，不影响已有审核、发布、安装和下载行为。
- 发布门禁本阶段只展示 `productionPromoted` 事实，不依据影响结果自动拒绝；后续在关系覆盖率和人工处置成熟后再评估接入受控门禁。

## 可验证结果

1. 关系可以按具体版本创建、查询和管理员撤销，重启后保持一致。
2. 重复 ACTIVE 关系、自环和任意环依赖被拒绝，且不产生脏记录。
3. 目标版本影响分析能返回直接/传递下游，去重、排序和深度/节点截断稳定。
4. 影响节点正确关联生命周期状态、生产晋级状态和活跃安装计数，不泄露主体或业务正文。
5. 无关系历史版本保持已有行为，Web/API 全量回归通过。
