# Optimization Experiment Decision Snapshot Design

日期：2026-08-24  
状态：实施基线

## 背景

SkillCenter 已能将优化建议转为优化工作项，使用 `OptimizationExperiment` 执行候选版本评测，并按同一套件、规则、数据源和执行环境生成 Benchmark。当前实验在 `COMPLETED` 后只保存质量快照和可选 Benchmark ID，管理员仍需人工阅读多个证据才能形成“是否继续候选版本”的结论，持续优化闭环缺少稳定、可审计的决策节点。

## 目标

为每个完成且已生成 Benchmark 的优化实验提供一份幂等、可恢复、不可篡改语义的决策快照。决策只读取实验上下文、候选质量门禁和 Benchmark 结论，输出稳定决策、证据引用、原因和推荐动作，不改变 Skill、版本、工作项或发布状态。

## 非目标

- 不自动发布、灰度、回滚或修改 Skill 包。
- 不自动完成或推进优化工作项。
- 不采集 Prompt、输入输出正文、工具参数、文件正文或凭据。
- 不引入调度器、在线学习或新的 Agent/Provider 执行框架。
- 不跨数据源、套件版本或 Runtime/MCP/LLM 环境拼接证据。

## 决策规则

决策只允许对 `COMPLETED` 且已有 Benchmark 的实验生成。若实验尚未完成或缺少 Benchmark，API 返回稳定的实验状态错误，前端明确提示缺少证据。

| 条件 | 决策 | 推荐动作 | 原因码 |
| --- | --- | --- | --- |
| 质量快照门禁为 `BLOCKED` | `REJECT_CANDIDATE` | 修复质量门禁问题并重新评测 | `QUALITY_GATE_BLOCKED` |
| 门禁通过，Benchmark 为 `IMPROVED` | `PROMOTE_CANDIDATE` | 提交人工发布审核 | `BENCHMARK_IMPROVED` |
| Benchmark 为 `REGRESSED` | `REJECT_CANDIDATE` | 保留基线并修复回归 | `BENCHMARK_REGRESSED` |
| Benchmark 为 `MIXED` 或 `NO_CHANGE` | `ITERATE` | 针对受影响指标继续优化 | `BENCHMARK_NEEDS_ITERATION` |
| Benchmark 为 `NOT_COMPARABLE` 或未知结论 | `NOT_COMPARABLE` | 补齐同口径基线证据 | `BENCHMARK_NOT_COMPARABLE` |

## 数据与接口

`OptimizationExperiment` 新增可选 `decision` 字段，决策与实验状态在同一 JSON 原子记录中保存，避免独立决策表产生孤儿记录。`OptimizationExperimentDecision` 至少包含：决策 ID、实验 ID、Skill/版本、数据源、Runtime/MCP/LLM、套件 ID/版本、质量快照 ID、Benchmark ID、门禁状态、Benchmark 结论、决策、原因码、推荐动作、评估人和评估时间。

新增管理员接口：

- `POST /api/v1/admin/quality/optimization-experiments/{experimentId}/decision`：计算并持久化决策；已有决策重复调用直接返回原快照。
- `GET /api/v1/admin/quality/optimization-experiments/{experimentId}/decision`：读取已持久化决策；尚未生成时返回 404 稳定错误。

服务必须校验质量快照和 Benchmark 与实验的 Skill、候选版本、数据源、执行环境及套件上下文完全一致。决策对象一旦保存不可替换；如证据被保留策略清理，历史实验仍保留决策与证据 ID，但不能重新生成。

## 前端行为

质量中心实验行：

- `COMPLETED + benchmarkId + 无决策` 显示“生成决策”。
- 有决策时展示决策、原因和推荐动作；不显示业务正文。
- 缺少 Benchmark 时显示“先生成 Benchmark”，不发起决策请求。
- 所有决策操作只对管理员开放，失败时保留当前实验和错误提示。

## 错误与审计

- 缺失实验：`OPTIMIZATION_EXPERIMENT_NOT_FOUND`，HTTP 404。
- 非完成状态或缺少 Benchmark：`OPTIMIZATION_EXPERIMENT_DECISION_INVALID_STATE`，HTTP 409。
- 证据上下文不一致：同一错误码，HTTP 409。
- JSON 持久化失败：`OPTIMIZATION_EXPERIMENT_PERSISTENCE_FAILED`，HTTP 503。
- 成功生成决策写入 `OPTIMIZATION_EXPERIMENT_DECIDED` 脱敏审计事件，仅包含 ID、决策、原因码和状态。

## 验收标准

1. 五类决策规则均有单元测试，且测试先失败再实现。
2. 决策生成幂等，重启恢复后返回同一决策 ID 和内容。
3. 上下文不一致、缺证据、权限不足和持久化失败均返回稳定错误。
4. API 与 Quality Center 交互覆盖生成、展示和前置条件提示。
5. API 全量测试、Web 全量测试、Web 构建和 `git diff --check` 通过。
6. 既有“实验不自动完成工作项/发布 Skill”的行为保持不变。
