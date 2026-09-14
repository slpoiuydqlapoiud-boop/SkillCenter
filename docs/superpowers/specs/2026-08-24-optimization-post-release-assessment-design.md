# 优化实验发布后效果评估设计

## 目标

在发布后运行观察之上建立可审计的“指标 → 结论 → 人工处置”闭环，比较候选版本与源版本的同口径运行表现，并把结论作为下一轮优化工作的可复用证据。

## 核心模型

`OptimizationExperimentAssessment` 是不可变快照，包含：

- 实验、工作项、Skill、源版本、候选版本、数据源、Runtime/MCP/LLM 和不可变评测套件上下文；
- 被评估的候选观察 ID，以及以同一窗口结束时间采集的源版本基线指标；
- 候选/基线的调用数、成功数、失败数、超时数、成功率和 P95；
- 评估时阈值快照：最小运行样本、最低成功率、最大 P95；
- 结论、稳定原因码、推荐动作、人工处置动作、备注、评估人和时间。

结论仅由纯规则计算，不触发 Skill 执行或外部网络调用：

| 条件 | 结论 | 原因码 | 推荐动作 |
| --- | --- | --- | --- |
| 候选或基线样本低于最小样本 | `INSUFFICIENT_TRAFFIC` | `RUNTIME_SAMPLES_BELOW_MINIMUM` | `CONTINUE_OBSERVING` |
| 候选成功率低于基线、P95 高于基线，或违反当前阈值 | `REGRESSION` | `POST_RELEASE_REGRESSION` | `ROLLBACK_REVIEW` |
| 候选不回退且同时满足成功率/P95阈值 | `HEALTHY` | `POST_RELEASE_HEALTHY` | `KEEP` |
| 指标无变化或一项改善、一项退化 | `INCONCLUSIVE` | `POST_RELEASE_INCONCLUSIVE` | `CREATE_FOLLOW_UP` |

人工处置动作只允许 `KEEP`、`CREATE_FOLLOW_UP`、`ROLLBACK_REVIEW`。动作不会自动回滚版本、修改发布状态、推进 Provider readiness 或创建工作项；`CREATE_FOLLOW_UP` 仅保留下一轮优化建议和可复用 Assessment ID。

## API

```text
GET  /api/v1/admin/quality/optimization-experiments/{experimentId}/assessments
GET  /api/v1/admin/quality/optimization-experiments/{experimentId}/assessments/{assessmentId}
POST /api/v1/admin/quality/optimization-experiments/{experimentId}/assessments
     body: { "observationId": "observation-...", "action": "KEEP", "note": "..." }
```

创建前必须满足实验为 `COMPLETED`、决策为 `PROMOTE_CANDIDATE`、候选版本曾经发布且观察 ID 属于该实验。基线使用观察的 `capturedAt` 作为窗口结束时间，保证与候选观察的窗口口径一致。

## 审计与安全

每次创建评估写入 `OPTIMIZATION_EXPERIMENT_ASSESSED`，只记录稳定 ID、结论、阈值和运行聚合；不记录 Prompt、输入输出、Trace 正文、工具参数或凭据。Assessment 与工作项通过 `workItemId` 和受控 `assessmentId` 关联，绑定时必须匹配套件 ID/版本，避免跨套件串证据；观察与评估记录按调用保留期进入统一 RetentionService 的质量证据清理统计。
