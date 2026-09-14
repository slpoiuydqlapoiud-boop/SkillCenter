# Skill 优化工作项与实验迭代闭环设计

日期：2026-08-24  
状态：实施中

## 目标

将现有的质量快照、Benchmark、运行聚合和优化建议，沉淀为可追踪的优化工作项，使 Skill 具备“发现问题—制定假设—产生候选版本—评测验证—完成/放弃”的软件工程迭代闭环。

## 范围

本阶段包含：

- 管理员从已存在的优化建议创建优化工作项；
- 记录源 Skill 版本、建议快照、数据来源和执行环境上下文；
- 通过受控状态机推进计划、实施、待评测、完成或放弃；
- 绑定已完成的质量评测、质量快照或 Benchmark 作为结果证据；
- JSON 原子持久化、重启恢复、唯一性校验和审计；
- 质量中心展示、创建和推进工作项。

本阶段不包含：

- 在线编辑 Skill 正文或自动生成/修改 Skill 包；
- 任意代码执行、自动发布或自动回滚；
- 外部工单系统、真实 Provider 或企业 SSO 接入；
- 用业务 Prompt、输入输出正文或凭据填充工作项。

## 领域模型

`OptimizationWorkItem` 是不可丢失上下文的治理记录，保存建议生成时的安全摘要，而不是每次查询时重新推导：

| 字段 | 说明 |
| --- | --- |
| `workItemId` | UUID 字符串，创建后不变 |
| `skillId` | Skill 标识 |
| `sourceVersion` | 触发问题的源版本 |
| `suggestionId` | 优化建议稳定 ID |
| `suggestionTitle/category/severity` | 建议摘要快照 |
| `suggestionEvidence` | 仅保存安全指标键值摘要 |
| `hypothesis` | 管理员对优化方向的可审计假设，最多 500 字符 |
| `ownerId` | 负责推进的主体，默认创建者 |
| `status` | 工作项状态 |
| `candidateVersion` | 待验证的候选版本，可在进入评测前绑定 |
| `evidenceType/evidenceId` | 评测运行、质量快照或 Benchmark 引用 |
| `outcome` | 完成/放弃说明，最多 500 字符 |
| `dataSource/runtimeId/mcpServerId/llmProviderId` | 证据口径上下文 |
| `createdBy/createdAt/updatedBy/updatedAt` | 审计元数据 |

状态集合：`OPEN`、`PLANNED`、`IN_PROGRESS`、`READY_FOR_EVALUATION`、`COMPLETED`、`ABANDONED`。

允许的状态转移：

```text
OPEN -> PLANNED -> IN_PROGRESS -> READY_FOR_EVALUATION -> COMPLETED
  |         |             |                 |
  +---------+-------------+-----------------+--> ABANDONED

ABANDONED -> OPEN
```

`COMPLETED` 不允许再次修改；重新优化必须创建新的工作项。进入 `READY_FOR_EVALUATION` 必须已有候选版本；进入 `COMPLETED` 必须已有匹配的完成证据和非空结果说明。`ABANDONED` 可带原因，重新打开时清除结果证据但保留历史审计。

## API

所有接口位于 `/api/v1/admin/quality/optimization-work-items`，仅管理员可访问。

- `POST /`：从 `skillId`、`sourceVersion`、`suggestionId`、`hypothesis` 和可选环境上下文创建 `OPEN` 工作项；同一 Skill/源版本/建议 ID 存在未终态工作项时返回冲突。
- `GET /?skillId=&status=&ownerId=&sourceVersion=`：按治理字段过滤，按更新时间倒序返回。
- `GET /{workItemId}`：查询单条工作项。
- `PATCH /{workItemId}/status`：请求 `{ "status": "IN_PROGRESS", "candidateVersion": "2.1.0", "outcome": "..." }`，执行状态机和候选版本校验。
- `PUT /{workItemId}/evidence`：请求 `{ "evidenceType": "BENCHMARK", "evidenceId": "benchmark-...", "outcome": "..." }`；只允许绑定与 Skill、候选版本、数据来源和环境一致的证据。

错误码：`OPTIMIZATION_WORK_ITEM_NOT_FOUND`、`OPTIMIZATION_WORK_ITEM_CONFLICT`、`OPTIMIZATION_WORK_ITEM_INVALID_STATE`、`OPTIMIZATION_WORK_ITEM_EVIDENCE_INVALID`、`OPTIMIZATION_WORK_ITEM_PERSISTENCE_FAILED`。

## 持久化与审计

使用独立的 `OptimizationWorkItemStore`，默认路径为 `./data/governance/optimization-work-items.json`。写入采用临时文件加原子替换；启动恢复拒绝重复 ID、重复未终态业务键、非法状态、时间倒序和不合法引用。任何创建、状态变更和证据绑定都通过 `GovernanceStore` 写入安全审计字段，不记录业务正文。

## 前端交互

质量中心增加“优化工作项”面板：

- 展示当前 Skill/版本和环境口径下的工作项；
- 从当前建议创建工作项，填写假设；
- 为工作项选择状态、候选版本和结果说明；
- 绑定当前可用的评测/Benchmark 证据；
- 终态和证据不匹配时显示明确错误，不隐藏或自动修改 Skill。

## 验收标准

1. 管理员能从一个真实建议创建工作项，刷新或重启后仍保留建议快照和环境上下文。
2. 非法状态转移、重复未终态工作项、缺少候选版本或不匹配证据均被拒绝并返回稳定错误码。
3. 只有候选版本对应的已完成评测、质量快照或 Benchmark 可以完成工作项。
4. 完成工作项不可修改；重新打开已放弃工作项必须有审计记录。
5. Web/API 测试覆盖状态机、持久化恢复、权限、证据一致性、前端创建和状态更新；既有全量回归保持通过。
