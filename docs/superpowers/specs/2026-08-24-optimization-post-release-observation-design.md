# 优化实验发布后观察设计

## 目标

把优化闭环从“决策允许发布”延伸到“发布后可验证”。管理员可以对已经发布且决策为 `PROMOTE_CANDIDATE` 的候选版本采集运行聚合快照，并将快照作为不可变、可审计的实验观察证据。

## 约束

- 观察只读聚合 `RuntimeOperationsService`，不保存 Prompt、输入输出、Trace 正文、工具参数或凭据。
- 仅 `COMPLETED + PROMOTE_CANDIDATE` 实验可观察；候选版本必须存在 `publishedAt`。
- 观察记录追加写入 JSON，按 `capturedAt` 倒序查询；不覆盖历史快照。
- 无流量明确标记 `NO_TRAFFIC`，有流量标记 `CAPTURED`；不在本阶段推断回滚或自动改变发布状态。
- 所有采集写入 `OPTIMIZATION_EXPERIMENT_POST_RELEASE_OBSERVED` 审计事件，保留实验、证据和环境上下文。

## API

```text
GET  /api/v1/admin/quality/optimization-experiments/{experimentId}/observations
POST /api/v1/admin/quality/optimization-experiments/{experimentId}/observations
     body: { "window": "24h" }
```

响应只返回窗口、数据源、环境标识、调用总数、成功/失败/超时/取消计数、成功率、P95 和观察状态。

## 后续演进

后续可在不改变观察记录格式的前提下增加基于多次快照的回归阈值、人工复盘结论和回滚工单关联；这些动作必须继续保持显式授权和独立审计。
