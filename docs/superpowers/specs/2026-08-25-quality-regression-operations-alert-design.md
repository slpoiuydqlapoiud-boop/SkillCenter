# 质量回归运营告警设计

## 背景

Skill Center 已经能够在发布后评估中产出 `HEALTHY` 或 `REGRESSION` 结论，也能将回归处置转化为优化工作项；但运营告警只覆盖运行时指标、生命周期投影、工作项陈旧和生产交付证据，质量回归尚未进入统一的告警状态机。因此质量问题可能只停留在质量中心页面，不能被值班人员及时发现，也没有统一的恢复信号。

## 目标与范围

本增量将发布后评估中的最新结论汇总为平台级质量回归信号，并接入现有 `OperationsAlertService`：

1. 对每个 Skill 只保留其最新发布后评估，避免旧版本回归在新评估后继续误报。
2. 只把 `REGRESSION` 计为活动质量回归；没有评估或非回归状态不触发。
3. 评估存储不可用、记录非法或探针异常时，进入 `NOT_READY`，并使用稳定事件码告警，不能静默为健康。
4. 沿用现有告警状态持久化和通知转换语义：活动、重复评估不重复通知，恢复时发送一次恢复通知。
5. 不修改发布、回滚、工作项状态，不自动执行处置；告警只提供运营信号和证据定位线索。

本增量不引入新的外部依赖、定时任务、数据库表或前端专用接口；运营告警现有接口会自动返回新增规则。

## 设计

### 质量回归探针

新增 `QualityRegressionProbe` 接口和 `QualityRegressionService` 实现。服务读取 `OptimizationExperimentAssessmentStore.findAll("")`，按 `skillId` 归并，并按 `assessedAt`、`assessmentId` 倒序选出每个 Skill 的最新评估。输出不可变的 `QualityRegressionHealth`：

- `status`: `HEALTHY`、`DEGRADED` 或 `NOT_READY`；
- `reasonCode`: `QUALITY_REGRESSIONS_DETECTED`、`QUALITY_REGRESSIONS_HEALTHY` 或 `QUALITY_REGRESSION_SIGNAL_UNAVAILABLE`；
- `checkedAt`、评估 Skill 数、回归数和有限的回归条目（Skill、源版本、候选版本、评估 ID、结论、原因码）。

所有列表按稳定字段排序，回归条目最多保留 100 条，避免告警读取受到数据规模影响。存储异常统一转换为 `NOT_READY`，不向运营接口泄露内部异常细节。

### 运营告警

`OperationsAlertService` 通过 `ObjectProvider<QualityRegressionProbe>` 可选接入探针，保持现有测试构造器和无质量存储场景兼容。新增固定状态键 `QUALITY_REGRESSION`，使用规则 `QUALITY_REGRESSION`，阈值为 0、单位为 `count`：

- `REGRESSION` 或 `NOT_READY` 时 `ACTIVE`；
- `HEALTHY` 时 `RESOLVED`；
- 状态键固定，事件码可变化，确保 `REGRESSION` → `HEALTHY` 能正确产生恢复通知。

### 错误与兼容性

质量探针为空时不新增告警，适用于现有单元测试和非 Spring 轻量构造；Spring 运行时若质量评估依赖未就绪，探针仍被发现并返回不可用告警。现有 `OperationsAlertSnapshot` 数据结构不变，避免破坏 API、Web 客户端和已有消费者。

## 验收标准

1. 最新评估为回归时，告警返回 `QUALITY_REGRESSION/QUALITY_REGRESSIONS_DETECTED/ACTIVE`，当前值等于回归 Skill 数。
2. 最新评估恢复为健康时，同一规则返回 `RESOLVED`，并只触发一次恢复通知。
3. 探针异常或存储不可用时，返回 `QUALITY_REGRESSION_SIGNAL_UNAVAILABLE/ACTIVE`。
4. 同一 Skill 的旧回归在后续健康评估后不再计数；多个 Skill 的回归按 Skill 去重。
5. API 全量测试、Web 全量测试、Web 构建和生命周期验证全部通过。
