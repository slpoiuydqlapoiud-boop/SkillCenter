# A3-1 Skill 生命周期关系投影设计

日期：2026-08-25  
状态：设计基线，供实施计划评审

## 1. 目标

A3-1 在 A2 Quality Evidence PostgreSQL 适配器之上，建立 Skill 资产生命周期事实的可回滚关系投影。投影覆盖 Skill、版本、发布、范围和版本关系五类可审计事实，使企业能够按 Skill/版本查询生命周期状态、生产发布事实和影响关系，同时不改变现有 JSON 默认写入路径。

本阶段完成后，平台能够：

- 从现有 JSON 事实源生成确定性的生命周期投影，并记录源快照 SHA-256、导入批次和 revision；
- 通过 PostgreSQL 约束和事务保证投影内部的主键、外键、唯一键和导入原子性；
- 在管理员控制面执行预检、显式导入、状态查询和 Skill/版本影响查询；
- 对相同源快照幂等复用，对源快照变化要求显式重新导入；
- 在数据库不可用、schema 未完成、源事实损坏或上下文不一致时 fail-closed，不返回部分投影；
- 保持 JSON 为默认事实源，不做运行时双写、在线切换或把投影伪装为主写库。

## 2. 当前事实源与边界

当前生命周期事实分布在以下稳定端口：

- `GovernanceStore.snapshot()`：`SkillVersion`、审核、安装、授权、审计等治理快照；本阶段只读取 `versions`；
- `ReleaseRecordStore.findAll(null, null, null, null)`：受控发布批次和状态；
- `SkillScopeStore.findAll()`：Skill 可见范围、owner team、维护者和 revision；
- `SkillRelationStore.findAll(null, null, null, null, null)`：版本间 `DEPENDS_ON`、`COMPOSES`、`REPLACES` 关系；
- `QualityEvidenceRepository`：质量证据仍由 A2 独立管理，本阶段不复制质量正文；生命周期查询只通过现有质量服务按 Skill/版本关联，不把 Prompt、输入输出或 Trace 写入投影。

`SkillRecord` 的展示文本、制品路径、Prompt、输入输出和 Trace 正文不进入关系投影。投影是生命周期查询模型，不是制品存储或新的 Skill 内容仓库。

## 3. 方案与取舍

### 3.1 采用：离线源快照 + PostgreSQL 关系投影

新增 `SkillLifecycleProjectionService` 聚合现有 JSON 事实源，规范化排序后计算源 SHA-256，生成不可变 `SkillLifecycleProjectionSnapshot`。`PostgresSkillLifecycleProjectionStore` 在一个事务中替换五类投影表和元数据表；查询只读这些表。

导入是显式管理员动作，不由普通 Skill 请求、启动生命周期或查询接口隐式触发。投影数据库不可用时，JSON 业务链路继续按现有配置运行；只有显式启用 PostgreSQL 投影并执行导入时，投影状态才报告失败。

### 3.2 不采用：运行时双写

本阶段不在 `GovernanceStore`、`ReleaseRecordStore`、`SkillScopeStore` 或 `SkillRelationStore` 写入后同步 PostgreSQL。双写会制造跨 Store 事务不一致，并把 JSON 和 PostgreSQL 同时变成事实源。后续若迁移写入源，必须另立设计并提供事务边界、补偿和切换审批。

### 3.3 不采用：全量 JPA 关系化或制品迁移

不迁移审核、安装、通知、调用明细、质量正文、制品 ZIP、Prompt、Trace、授权 Token 或治理配置；这些领域继续使用现有端口和存储。A3-1 只建立可独立验证的生命周期查询与迁移桥。

## 4. 配置与选择

```yaml
skill-center:
  lifecycle-projection:
    backend: json
```

允许值：`json`、`postgresql`，默认 `json`。

- `json`：不创建额外 DataSource，不执行 PostgreSQL 导入；`JsonSkillLifecycleProjectionStore` 每次从当前已校验 JSON 事实源生成兼容查询结果。
- `postgresql`：必须同时满足全局 `skill-center.persistence.backend=postgresql`、A2 PostgreSQL 配置有效、Flyway schema 完成和投影 Repository wiring 完整；否则状态为 `FAIL_CLOSED`。
- 投影 backend 不自动改变 `QualityEvidenceRepository`、`GovernanceStore` 或发布 Store 的实际写入 backend。
- 不提供请求级 backend 切换，不从请求体接收表名、列名、SQL、数据库 URL 或文件路径。

## 5. 数据模型

Flyway V2 创建以下表。所有时间使用 `timestamptz`，所有枚举以受控文本保存，所有 ID 使用参数绑定。

### 5.1 `skill_lifecycle_projection_meta`

- `projection_key text primary key`，固定值 `skill-lifecycle`；
- `schema_version integer not null`；
- `revision bigint not null check (revision >= 0)`；
- `source_sha256 text not null`；
- `source_generated_at timestamptz not null`；
- `imported_at timestamptz not null`；
- `skill_count integer not null check (skill_count >= 0)`；
- `version_count integer not null check (version_count >= 0)`；
- `release_count integer not null check (release_count >= 0)`；
- `scope_count integer not null check (scope_count >= 0)`；
- `relation_count integer not null check (relation_count >= 0)`。

Flyway V2 seeds the fixed row with revision `0`, a 64-zero source hash and zero counts so concurrent first imports can lock `projection_key = 'skill-lifecycle'` with `SELECT projection_key FROM skill_lifecycle_projection_meta WHERE projection_key = 'skill-lifecycle' FOR UPDATE`; the seed is metadata only and does not claim that an empty projection represents imported business facts。

### 5.2 `skill_lifecycle_skill_projection`

主键 `skill_id`。保存 `latest_version`、`latest_status`、`version_count`、`published_version_count`、`active_release_count`、`visibility`、`owner_team_id`、`scope_revision`、`source_sha256` 和 `updated_at`。不保存展示正文或制品路径。

### 5.3 `skill_lifecycle_version_projection`

主键 `(skill_id, version)`；保存 `package_id`、`status`、`sha256`、`size_bytes`、`uploaded_by`、`uploaded_at`、`published_at`、`risk_level`、`source_sha256` 和 `updated_at`。`skill_id` 外键引用 Skill 表；同一 Skill 的版本号不可重复。

### 5.4 `skill_lifecycle_release_projection`

主键 `release_id`；保存 Skill/版本、目标环境、状态、质量门禁 outcome、SHA-256、审批/更新时间、`source_assessment_id` 和回滚目标引用。`(skill_id, version, target_environment, release_id)` 建索引，不复制 gate 详情中的业务正文。

### 5.5 `skill_lifecycle_scope_projection`

主键 `skill_id`；保存 visibility、owner team、维护者数量、scope revision、声明/更新时间。维护者 ID 只用于管理员授权查询，不在普通投影查询响应中返回。

### 5.6 `skill_lifecycle_relation_projection`

主键 `relation_id`；保存源/目标 Skill 版本、关系类型、状态、声明/退休时间、source hash。源和目标必须存在于版本表；数据库不承担通用图算法，导入服务继续执行现有无环和重复校验。

## 6. 稳定接口

```java
public interface SkillLifecycleProjectionRepository {
    ProjectionStatus status();
    ProjectionImportResult replace(SkillLifecycleProjectionSnapshot snapshot);
    Optional<SkillLifecycleProjectionView> findSkill(String skillId);
    List<SkillLifecycleProjectionView> findSkills(SkillLifecycleProjectionQuery query);
    List<SkillLifecycleImpactView> findImpact(String skillId, String version);
}
```

`replace` 必须在一个事务内完成：校验源 hash 和所有上下文 → 锁定 meta → 在同一事务中删除并重建子表/父表行 → 校验行数/外键/唯一键 → 更新 meta revision。事务提交前读者继续看到上一版已提交投影；任何异常回滚整次导入，不允许留下部分 Skill 或关系。

`SkillLifecycleProjectionService` 提供：

- `preflight()`：读取现有 JSON 事实源，返回 source SHA-256、计数、当前投影是否相同和稳定差异码，不修改数据库；
- `importSnapshot(String expectedSourceSha256, String actor)`：只有 expected hash 与刚生成的源 hash 相同才导入，重复 hash 幂等；
- `findSkills`：按 Skill、版本、生命周期状态、目标环境和可见性筛选，结果只返回安全聚合字段；
- `findImpact`：返回版本关系、相关发布状态和生产晋级事实，不返回安装用户、Token、Prompt、Trace 或原始异常。

管理员 API：

- `GET /api/v1/admin/skill-lifecycle/projection/status`；
- `POST /api/v1/admin/skill-lifecycle/projection/preflight`；
- `POST /api/v1/admin/skill-lifecycle/projection/import`；
- `GET /api/v1/admin/skill-lifecycle/projection/skills`；
- `GET /api/v1/admin/skill-lifecycle/projection/skills/{skillId}/impact`。

所有写操作要求管理员和 requestId；导入审计只记录 actor、requestId、source hash、计数、revision、稳定结果码，不记录源 JSON、制品路径、Prompt 或数据库异常正文。

## 7. 一致性与安全

- 源快照按固定字段顺序、稳定枚举文本、UTC 时间和 ID 排序后计算 SHA-256；JSON 字段顺序不影响 hash；
- 相同 source hash 导入必须幂等，不增加 revision；新 hash 才以 `revision + 1` 写入；revision 溢出拒绝导入；
- 导入前复用现有领域 Store 的恢复校验；缺失版本、重复发布业务键、坏范围、环关系或源内容损坏以稳定 `SKILL_LIFECYCLE_PROJECTION_SOURCE_INVALID` 失败；
- PostgreSQL 连接、Flyway、约束或事务失败返回 `SKILL_LIFECYCLE_PROJECTION_NOT_READY`/`SKILL_LIFECYCLE_PROJECTION_IMPORT_FAILED`，不泄露 SQL、URL、路径或异常 cause；
- Scope 查询必须继续经过 `SkillAuthorizationService`；管理员投影接口不能成为绕过现有 Skill 可见性边界的旁路；
- 不允许通过投影查询反推出隐藏 Skill 的 owner team、维护者、安装用户或下游关系 ID；
- JSON 默认模式不依赖 PostgreSQL，且 A1 文件快照不覆盖投影数据库；本阶段不提供数据库备份或恢复伪装。

## 8. 测试策略

必须先 RED 后 GREEN：

- 快照规范化：同一事实不同列表顺序/JSON 字段顺序产生相同 hash，不同 lifecycle 字段产生不同 hash；
- 源校验：缺失版本、重复主键、关系目标不存在、非法 scope、循环关系和非法 release 上下文均 fail-closed；
- JSON 默认：应用上下文不创建投影 DataSource，已有 Skill/质量/发布 API 保持兼容；
- JDBC Repository：空状态、一次性导入、多表 round-trip、重复 hash 幂等、新 hash revision 增长、约束失败全量回滚、revision 溢出保护；
- 查询边界：管理员查询返回聚合字段，隐藏字段和敏感正文不出现；普通用户、维护者和非管理员不能通过投影端点绕过授权；
- API：预检只读、导入需要 hash/actor/requestId、错误码稳定、重复请求幂等；
- 真实 PostgreSQL 优先使用 Testcontainers；Docker 不可用时只对命名集成测试做 capability skip，不能跳过编译、单元、JSON 兼容和契约测试。

## 9. 非目标与后续阶段

本阶段不实现：

- GovernanceStore、ReleaseRecordStore、SkillScopeStore 或 SkillRelationStore 的运行时写入替换；
- PostgreSQL 与 JSON 运行时双写、在线切换、自动回退或数据库覆盖恢复；
- Prompt/制品/Trace/调用正文的关系化或对象存储迁移；
- 真实生产数据库供应、PITR、备份恢复演练、SSO/JWT、多实例一致性和真实 Provider readiness；
- 依据投影自动发布、回滚、撤销授权或改变现有生命周期状态。

A3 后续阶段可在本投影和导入审计稳定后，按 `SkillVersion → ReleaseRecord → SkillScope/Relation → Governance` 顺序将单域写入迁移为 PostgreSQL，并为每一步增加双读对账、切换审批和可恢复回滚。

## 10. 验收标准

1. JSON 默认应用启动和现有全量回归不依赖投影数据库。
2. PostgreSQL 模式只有显式配置和 Flyway V2 完成后才创建投影 Repository。
3. 相同源 hash 导入幂等；不同源 hash 产生单调 revision；失败导入数据库状态保持不变。
4. Skill/版本/发布/范围/关系可查询，关系和发布影响结果与 JSON 事实源一致。
5. 查询和审计不泄露 Prompt、Trace、输入输出、Token、凭据、路径或原始异常。
6. 真实 PostgreSQL 可用时执行事务/约束/锁定测试；不可用时明确记录能力跳过，不伪造生产就绪。
