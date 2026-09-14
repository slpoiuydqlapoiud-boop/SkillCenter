# 受控发布晋级与回滚评审设计

## 目标

在现有 Skill 版本审核、质量门禁和发布后效果评估之上，增加独立的发布控制面，使“版本已发布”与“版本已晋级到某个运行环境”成为两个可审计、可验证的生命周期事实。

本阶段只建设控制面和可替换执行端口：本地使用确定性 Mock Release Target 验证状态机，真实 Agent Runtime、MCP 或企业部署系统通过后续适配器接入；不伪造生产部署成功，不自动回滚。

实现基线：当前控制面已落地为 `release` 模块、原子 JSON Store、管理员 API 和 Quality Center 显式操作面；默认 Mock Target 仅用于本地演练，外部目标仍需通过 `ReleaseTarget` 契约接入并保持 fail-closed。

## 现状与边界

- `ReviewService.approve` 当前直接将 `SkillVersion` 写为 `published`，这是资产可发现/可分发状态，不代表已部署到 staging 或 production。
- `VersionLifecycleService` 只负责 `published -> deprecated -> withdrawn`，不能表达环境晋级、回滚或发布批次。
- `QualityReleaseGate` 已能在审核发布前校验质量、优化实验和兼容性矩阵，但当前没有冻结门禁结果的发布证据快照。
- 发布后效果评估可以推荐 `ROLLBACK_REVIEW`，但当前只能产生评估记录，不能进入受控人工回滚评审队列。

本阶段不改变既有 `SkillVersion.status` 语义，不把 `STAGING`/`PRODUCTION` 写进 Skill 版本状态，也不改变历史版本的发布兼容行为。

## 核心模型

### ReleaseRecord

`ReleaseRecord` 是可变状态、不可变上下文的发布批次聚合。以下字段在创建后不可修改：

- `releaseId`、`skillId`、`version`、`artifactSha256`；
- `targetEnvironment`：`STAGING` 或 `PRODUCTION`；
- 请求时的 `qualityGateSnapshot`；
- 可选的 `sourceAssessmentId`、`rollbackOfReleaseId` 和执行环境上下文；回滚评审另保存 `rollbackAssessmentId`、目标版本和目标发布批次；
- `idempotencyKey`、`requestedBy`、`requestedAt`。

状态只允许沿状态机转换：

```text
REQUESTED -> APPROVED -> PROMOTING -> PROMOTED
REQUESTED -> REJECTED
APPROVED  -> REJECTED
PROMOTED  -> ROLLBACK_REVIEW -> ROLLING_BACK -> ROLLED_BACK
PROMOTING -> FAILED
ROLLING_BACK -> FAILED
```

`FAILED` 是终态；失败后必须新建发布批次或回滚评审，不允许覆盖原有执行结果。只有 `PROMOTED` 才代表目标 Release Target 返回成功。`REQUESTED`、`APPROVED` 和 `ROLLBACK_REVIEW` 都不是线上状态。

### GateSnapshot

发布请求或晋级审批时冻结：

- `checkedAt`、`outcome` (`PASSED`/`BLOCKED`/`NO_EVIDENCE`)、稳定原因码；
- 当前质量快照 ID、优化实验 ID/决策、兼容性矩阵 ID（有则记录）；
- `dataSource`、评测套件 ID/版本、Runtime/MCP/LLM 上下文（有则记录）。

快照不包含 Prompt、输入输出、Trace 正文、工具参数、Provider 异常正文或凭据。

## 状态与权限规则

- 仅 `admin` 可创建、拒绝、晋级和执行回滚；`reviewer` 可查看并批准 `STAGING` 发布，`PRODUCTION` 发布的批准必须由 `admin` 执行。
- 发布请求者不能批准同一发布批次；回滚评审提交者不能批准自己的回滚。
- 每个 `skillId + version + targetEnvironment` 最多一个非终态发布批次；相同 `idempotencyKey` 返回原记录。
- 请求版本必须存在且状态为 `published` 或 `deprecated`；制品哈希从治理快照读取，客户端不能覆盖。
- 目标为 `PRODUCTION` 时必须重新执行 `QualityReleaseGate` 并冻结结果；门禁阻断时不创建可晋级记录，并写入 `RELEASE_GATE_BLOCKED` 审计。
- `STAGING` 晋级同样需要门禁快照，但允许 `NO_EVIDENCE` 作为明确的本地/开发环境结果；`PRODUCTION` 不允许 `NO_EVIDENCE`。
- 只有已 `PROMOTED` 的发布批次可以进入回滚评审；回滚请求必须关联目标版本或原发布批次，且说明原因。
- 回滚只调用目标版本的 `ReleaseTarget.rollback`，不自动修改 SkillVersion，不自动删除版本，不自动切换 Provider readiness。

## ReleaseTarget 端口

核心域只依赖以下端口：

```java
public interface ReleaseTarget {
    ReleaseTargetResult promote(ReleaseRecord release);
    ReleaseTargetResult rollback(ReleaseRecord release);
}
```

`ReleaseTargetResult` 只允许返回状态、稳定错误码、外部关联 ID、耗时和完成时间。禁止返回或持久化部署响应正文、凭据、环境变量和业务载荷。

本地默认实现为 `MockReleaseTarget`，按发布批次 ID 确定性成功；外部目标未配置时使用 `CONTRACT_ONLY`/`RELEASE_TARGET_NOT_ENABLED` fail-closed 行为，不能把控制面记录标记为 `PROMOTED`。

## API

```text
POST /api/v1/admin/releases
     body: { "skillId": "...", "version": "...", "targetEnvironment": "STAGING|PRODUCTION", "idempotencyKey": "..." }

GET  /api/v1/admin/releases?skillId=&version=&targetEnvironment=&status=
GET  /api/v1/admin/releases/{releaseId}
POST /api/v1/admin/releases/{releaseId}/approve
POST /api/v1/admin/releases/{releaseId}/reject
     body: { "reason": "..." }
POST /api/v1/admin/releases/{releaseId}/promote
POST /api/v1/admin/releases/{releaseId}/rollback-review
     body: { "reason": "...", "assessmentId": "...", "targetVersion": "...", "targetReleaseId": "..." }
POST /api/v1/admin/releases/{releaseId}/rollback
```

写操作返回当前聚合和 requestId；重复请求按状态机/幂等规则返回同一稳定结果或稳定冲突错误。所有写操作记录 `RELEASE_REQUESTED`、`RELEASE_APPROVED`、`RELEASE_REJECTED`、`RELEASE_PROMOTED`、`RELEASE_PROMOTION_FAILED`、`RELEASE_ROLLBACK_REVIEWED`、`RELEASE_ROLLED_BACK` 等审计事件，只保存白名单元数据。

## 持久化与恢复

- `ReleaseRecordStore` 使用 JSON 原子写入，主键 `releaseId` 唯一，启动时校验上下文不可变字段和状态转换合法性。
- 写入目标成功后才进入 `PROMOTED`/`ROLLED_BACK`；目标失败保存 `FAILED` 和稳定错误码。
- 服务重启后 `REQUESTED`、`APPROVED`、`ROLLBACK_REVIEW` 可继续人工操作；`PROMOTING`/`ROLLING_BACK` 恢复为 `FAILED`，避免重复猜测外部执行结果。
- 本阶段不扩展 RetentionService；发布批次的保留期、环境当前版本指针和审计链清理将在建立环境引用模型后单独立项，当前所有发布记录保留。

## Quality Center

质量中心新增“发布控制”面板：

- 按 Skill/版本/目标环境查看发布批次、门禁快照和当前状态；
- 对 `REQUESTED` 执行批准/拒绝，对 `APPROVED` 执行晋级；
- 对 `PROMOTED` 发起回滚评审，并展示关联 Assessment 的结论、推荐动作和人工处置；
- 仅在明确的人工确认后调用晋级/回滚 API；不在页面加载或轮询中自动改变状态。

## 验证策略

- 领域测试：状态机、不可变上下文、终态、同业务键冲突、幂等、生产无证据阻断、回滚评审权限。
- 服务测试：质量门禁快照、制品哈希读取、审批职责分离、Mock Target 成功/失败、重启恢复和审计白名单。
- 控制器测试：管理员权限、稳定错误码、所有 API 路径和 requestId。
- Web 测试：发布状态展示、审批/晋级/回滚评审显式操作、失败信息脱敏，不自动调用写 API。
- 全量回归：Web 测试/构建、API Maven 测试、`git diff --check`；外部 Runtime 部署仍由联调手册和目标环境验收。

## 非目标

- 不实现真实 Kubernetes、OpenClaw、MCP Gateway 或企业 CD 平台部署。
- 不自动发布、自动回滚、自动切换 SkillVersion 状态或 Provider。
- 不把发布批次当作业务调用授权；实际调用授权仍由现有版本/分发策略控制。
