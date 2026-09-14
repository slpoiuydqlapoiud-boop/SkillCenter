# 优化实验决策感知发布门禁设计

日期：2026-08-24

## 1. 背景与目标

当前 Skill 发布审核已经检查质量快照和兼容性矩阵，但优化实验的决策快照只停留在 Quality Center 展示层。候选版本因此可能在没有明确实验结论的情况下进入发布审核，质量证据链没有真正约束生命周期状态。

本增量把优化实验决策接入现有 `QualityReleaseGate`，让实验结论成为候选版本发布的必要前置条件，同时保留人工审核和现有质量门禁。该增量不调用外部 Provider，不自动发布、不回滚、不修改实验或工作项状态。

## 2. 范围

### 2.1 包含

- 发布门禁读取 `OptimizationExperimentStore` 中与 `skillId + candidateVersion` 匹配的实验。
- 存在未结束实验时阻断发布。
- 最新结束实验失败或取消时阻断发布。
- 最新完成实验缺少决策快照时阻断发布。
- 最新完成实验决策不是 `PROMOTE_CANDIDATE` 时阻断发布。
- `PROMOTE_CANDIDATE` 通过后继续执行现有兼容性矩阵和质量快照门禁。
- 无优化实验的历史版本保持现有兼容行为。
- 所有阻断原因进入既有 `QualityGateBlockedException`，沿用既有 API 错误结构。

### 2.2 不包含

- 自动调用发布、回滚、工作项状态变更或通知。
- 自动重跑实验、修改决策快照或覆盖人工审核结果。
- OpenClaw、DeepEval、Langfuse 等真实外部适配器接入。
- 新增数据库、消息队列或跨服务协议。

## 3. 发布判定规则

门禁按以下顺序执行：

1. 查询同一 `skillId` 且 `candidateVersion == version` 的实验。
2. 若没有匹配实验，跳过优化实验门禁，继续原有规则。
3. 若存在任一 `QUEUED` 或 `RUNNING` 实验，阻断并返回 `OPTIMIZATION_EXPERIMENT_INCOMPLETE`。
4. 按 `updatedAt` 倒序、`experimentId` 倒序选取最新终态实验。
5. 若最新终态为 `FAILED` 或 `CANCELLED`，阻断并返回 `OPTIMIZATION_EXPERIMENT_FAILED` 或 `OPTIMIZATION_EXPERIMENT_CANCELLED`。
6. 若最新终态为 `COMPLETED` 但没有 `decision`，阻断并返回 `OPTIMIZATION_DECISION_REQUIRED`。
7. 若决策存在且不是 `PROMOTE_CANDIDATE`，阻断并返回 `OPTIMIZATION_DECISION_BLOCKED`。
8. 只有 `PROMOTE_CANDIDATE` 继续执行现有兼容性矩阵和质量快照检查。

门禁不依据实验创建者、请求者或前端状态判断；所有判断来自持久化实验记录。既有 `QualityGateBlockedException` 的 `skillId`、`version` 和 `reasons` 字段继续作为自动化契约。

## 4. 实现边界

`QualityEvaluationReleaseGate` 新增可选的 `OptimizationExperimentStore` 依赖，并保留原有构造函数，确保无实验依赖的单元测试和历史调用兼容。Spring 注入使用包含三个依赖的构造函数；旧构造函数将优化实验门禁视为未启用，仅用于兼容测试/嵌入式调用。

优化实验门禁在兼容性矩阵检查之前执行。这样即使实验明确建议促进，候选版本仍不能绕过矩阵和质量门禁；实验决策也不会替代人工 Review。

## 5. 测试要求

- 门禁无匹配实验时放行。
- 活动实验被 `OPTIMIZATION_EXPERIMENT_INCOMPLETE` 阻断。
- 最新失败/取消实验分别返回稳定原因。
- 完成但无决策返回 `OPTIMIZATION_DECISION_REQUIRED`。
- `ITERATE`、`REJECT_CANDIDATE`、`NOT_COMPARABLE` 返回 `OPTIMIZATION_DECISION_BLOCKED`。
- `PROMOTE_CANDIDATE` 通过优化实验门禁，并继续验证现有质量/矩阵规则。
- 既有 ReviewService 发布路径仍使用同一个门禁，并保持原有错误结构。

