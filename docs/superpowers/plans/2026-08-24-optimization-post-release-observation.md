# 优化实验发布后观察实施计划

## 交付范围

1. 建立 `OptimizationExperimentObservation` 领域模型和 JSON 原子持久化，校验计数、窗口和 `NO_TRAFFIC/CAPTURED` 状态。
2. 新增管理员观察服务，关联优化实验、已发布候选版本和 `RuntimeOperationsService` 快照，并写入审计事件。
3. 暴露观察查询/采集 API，保持非管理员拒绝、非 Promote/未发布候选阻断和旧实验兼容。
4. 在 Quality Center 展示观察证据并支持手动采集，不自动回滚或变更发布状态。
5. 完成 API 单测/控制器测试、Web 客户端/交互测试、全量测试和构建验证。

## 验收标准

- 观察快照重启后仍可读取且历史顺序稳定。
- 观察快照上下文与实验候选版本、数据源和执行环境一致。
- 非 `PROMOTE_CANDIDATE` 或未发布版本返回稳定冲突，不产生观察记录。
- 采集操作包含 `evidenceType=RUNTIME_OPERATIONS`、观察 ID 和环境字段的审计元数据。
- Web 能展示最近观察并在采集后立即更新；外部 Provider 仍保持 `CONTRACT_ONLY` 边界。
